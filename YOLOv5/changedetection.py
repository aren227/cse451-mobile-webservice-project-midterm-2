import os
import cv2
import time
import pathlib
import requests
from dataclasses import dataclass
from datetime import datetime

import numpy as np

from faceid import FaceIdentifier

PERSON = 0  # COCO 클래스 번호 0 = person


@dataclass
class Intruder:
    number: int
    feature: np.ndarray
    last_seen: float


class ChangeDetection:
    result_prev = []
    # 서버 주소와 계정은 환경변수로 바꿀 수 있음 (공개 레포에 비밀번호를 올리지 않기 위함)
    HOST = os.environ.get('BLOG_HOST', 'http://127.0.0.1:8000')
    username = os.environ.get('BLOG_USERNAME', 'admin')
    password = os.environ.get('BLOG_PASSWORD', 'password')
    token = ''
    title = ''
    text = ''

    def __init__(self, names):
        self.result_prev = [0 for i in range(len(names))]

        # 편의 기능 10-1: FACE_DB(등록 얼굴 폴더)를 지정하면 사람만 감시하고, 얼굴로 등록 인물과 침입자를 구분
        self.faceid = None
        face_db = os.environ.get('FACE_DB')
        if face_db:
            threshold = float(os.environ.get('FACE_THRESHOLD', '0.3'))
            self.faceid = FaceIdentifier(face_db, threshold)
        self.intruders = []
        self.memory_sec = float(os.environ.get('INTRUDER_MEMORY_SEC', '600'))  # 같은 침입자를 다시 게시하지 않는 시간
        self.person_prev = 0

        res = requests.post(self.HOST + '/api-token-auth/', {
            'username': self.username,
            'password': self.password,
        })
        res.raise_for_status()
        self.token = res.json()['token']  # 토큰 저장
        print('token:', self.token[:8] + '...')  # 캡처 화면에 토큰 전체가 남지 않도록 앞부분만 출력

    def add(self, names, detected_current, save_dir, image):
        if self.faceid:
            self.watch_person(detected_current[PERSON], save_dir, image)
            return

        self.title = ''
        self.text = ''
        change_flag = 0  # 변화 감지 플래그
        i = 0
        while i < len(self.result_prev):
            if self.result_prev[i] == 0 and detected_current[i] == 1:
                change_flag = 1
                self.title = names[i]
                self.text += names[i] + ", "
            i += 1

        self.result_prev = detected_current[:]  # 객체 검출 상태 저장

        if change_flag == 1:
            self.send(save_dir, image)

    def watch_person(self, person, save_dir, image):
        """사람만 감시: 등록 인물은 무시하고, 처음 보는 미등록 인물이 나타날 때마다 게시"""
        person_appeared = person == 1 and self.person_prev == 0
        self.person_prev = person
        if person == 0:
            return

        faces = self.faceid.identify(image)
        if not faces:
            # 얼굴이 안 보이면 신원을 확인할 수 없으므로 사람이 새로 나타났을 때만 게시
            print('얼굴: 확인 불가')
            if person_appeared:
                self.title = 'person'
                self.text = 'person (얼굴 미확인), '
                self.send(save_dir, image)
            return

        logs = []
        new_intruders = 0
        for face in faces:
            if face.name is not None:
                logs.append(f'{face.name} {face.score:.2f}')
                FaceIdentifier.draw(image, face, face.name)
                continue

            intruder, similarity = self.find_intruder(face.feature)
            if intruder is None:
                intruder = self.add_intruder(face.feature)
                new_intruders += 1
                logs.append(f'unknown #{intruder.number} (새 인물)')
            else:
                intruder.last_seen = time.time()  # 계속 보이는 동안은 다시 게시하지 않음
                logs.append(f'unknown #{intruder.number} (재식별 {similarity:.2f})')
            FaceIdentifier.draw(image, face, f'unknown #{intruder.number}')

        print('얼굴:', ', '.join(logs), f'-> 새 침입자 {new_intruders}명')
        if new_intruders == 0:
            return
        self.title = 'unknown person'
        if new_intruders == 1:
            self.text = 'unknown person, '
        else:
            self.text = f'unknown person x{new_intruders}, '
        self.send(save_dir, image)

    def find_intruder(self, feature):
        now = time.time()
        best, best_similarity = None, -1.0
        for intruder in self.intruders:
            if now - intruder.last_seen >= self.memory_sec:
                continue
            similarity = float(intruder.feature @ feature)
            if similarity > best_similarity:
                best, best_similarity = intruder, similarity

        if best_similarity < self.faceid.threshold:
            return None, None
        return best, best_similarity

    def add_intruder(self, feature):
        intruder = Intruder(number=len(self.intruders) + 1, feature=feature, last_seen=time.time())
        self.intruders.append(intruder)
        return intruder

    def send(self, save_dir, image):
        now = datetime.now()
        now.isoformat()

        today = datetime.now()
        save_path = os.getcwd() / save_dir / 'detected' / str(today.year) / str(today.month) / str(today.day)
        pathlib.Path(save_path).mkdir(parents=True, exist_ok=True)

        full_path = save_path / '{0}-{1}-{2}-{3}.jpg'.format(today.hour, today.minute, today.second, today.microsecond)

        dst = cv2.resize(image, dsize=(320, 240), interpolation=cv2.INTER_AREA)
        cv2.imwrite(str(full_path), dst)

        # 인증이 필요한 요청에 아래의 headers를 붙임 (DRF TokenAuthentication 형식: "Token <key>")
        headers = {'Authorization': 'Token ' + self.token, 'Accept': 'application/json'}

        # Post Create
        data = {
            'title': self.title,
            'text': self.text + now.strftime('%Y-%m-%d %H:%M:%S'),  # 본문 끝에 검출 시각
            'created_date': now,
            'published_date': now
        }
        with open(full_path, 'rb') as image_file:
            res = requests.post(self.HOST + '/api_root/Post/', data=data, files={'image': image_file}, headers=headers)
        print(res)
