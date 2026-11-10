"""등록 인물 얼굴 식별 (편의 기능 10-1).

- 얼굴 검출: YuNet (OpenCV Zoo)
- 얼굴 특징: LVFace-T (ICCV 2025, ByteDance) — ViT 기반 얼굴 인식 모델, ONNX를 OpenCV dnn으로 실행
  (가중치는 비상업 연구 목적으로만 사용 가능: https://github.com/bytedance/LVFace)
등록 폴더 구조: FACE_DB/<이름>/*.jpg  (사람마다 사진 여러 장 가능, 사진마다 본인 얼굴 하나만)
"""
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
import torch

ROOT = Path(__file__).resolve().parent
MODELS = {
    'face_detection_yunet_2023mar.onnx':
        'https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx',
    'LVFace-T_Glint360K.onnx':
        'https://huggingface.co/bytedance-research/LVFace/resolve/main/LVFace-T_Glint360K/LVFace-T_Glint360K.onnx',
}

FACE_SIZE = 112
# ArcFace 계열이 학습한 112x112 정렬 얼굴의 5개 랜드마크 위치 (눈 2, 코, 입꼬리 2)
TEMPLATE = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366],
                     [41.5493, 92.3655], [70.7299, 92.2041]], dtype=np.float32)

GREEN = (0, 200, 0)
RED = (0, 0, 255)


@dataclass
class Face:
    box: np.ndarray      # x, y, w, h
    feature: np.ndarray
    name: str | None     # 미등록 인물이면 None
    score: float         # 가장 닮은 등록 얼굴과의 코사인 유사도


def model_path(name):
    path = ROOT / name
    if not path.exists():
        print(f'downloading {name} ...')
        torch.hub.download_url_to_file(MODELS[name], str(path))
    return str(path)


class FaceIdentifier:
    def __init__(self, db_dir, threshold=0.3):
        self.threshold = threshold  # 코사인 유사도가 이 값 이상이면 같은 사람

        # OpenCV 5의 새 dnn 엔진이 모델을 열 때마다 내는 무해한 경고(setPreferableTarget) 숨김
        cv2.utils.logging.setLogLevel(cv2.utils.logging.LOG_LEVEL_ERROR)
        self.detector = cv2.FaceDetectorYN.create(model_path('face_detection_yunet_2023mar.onnx'), '', (320, 320), 0.8)
        self.recognizer = cv2.dnn.readNetFromONNX(model_path('LVFace-T_Glint360K.onnx'))

        self.gallery = self.load_gallery(db_dir)
        names = sorted({name for name, _ in self.gallery})
        print('등록 얼굴:', ', '.join(names), f'({len(self.gallery)}장)')

    def load_gallery(self, db_dir):
        gallery = []
        person_dirs = sorted(path for path in Path(db_dir).iterdir() if path.is_dir())
        for person_dir in person_dirs:
            for img_path in sorted(person_dir.glob('*.jpg')):
                img = cv2.imread(str(img_path))
                detections = self.detect(img)
                if len(detections) != 1:
                    continue  # 등록 사진에는 본인 얼굴 하나만 있어야 함
                feature = self.embed(self.align(img, detections[0]))
                gallery.append((person_dir.name, feature))
        return gallery

    def detect(self, img):
        """한 행이 얼굴 하나: [x, y, w, h, 랜드마크 5개의 x·y 10개, 신뢰도]"""
        height, width = img.shape[:2]
        self.detector.setInputSize((width, height))
        _, detections = self.detector.detect(img)
        if detections is None:
            return []
        return detections

    def align(self, img, detection):
        landmarks = detection[4:14].reshape(5, 2).astype(np.float32)
        matrix, _ = cv2.estimateAffinePartial2D(landmarks, TEMPLATE, method=cv2.LMEDS)
        return cv2.warpAffine(img, matrix, (FACE_SIZE, FACE_SIZE))

    def embed(self, aligned_face):
        # 픽셀 값을 -1~1로
        blob = cv2.dnn.blobFromImage(aligned_face, 1 / 127.5, (FACE_SIZE, FACE_SIZE), (127.5, 127.5, 127.5),
                                     swapRB=True)
        self.recognizer.setInput(blob)
        feature = self.recognizer.forward().flatten()
        return feature / np.linalg.norm(feature)

    def best_match(self, feature):
        best_name, best_score = None, -1.0
        for name, registered in self.gallery:
            score = float(registered @ feature)  # 둘 다 길이 1이라 내적이 곧 코사인 유사도
            if score > best_score:
                best_name, best_score = name, score
        return best_name, best_score

    def identify(self, img):
        faces = []
        for detection in self.detect(img):
            feature = self.embed(self.align(img, detection))
            name, score = self.best_match(feature)
            if score < self.threshold:
                name = None
            faces.append(Face(box=detection[:4].astype(int), feature=feature, name=name, score=score))
        return faces

    @staticmethod
    def draw(img, face, label):
        x, y, w, h = face.box
        color = RED if face.name is None else GREEN
        cv2.rectangle(img, (x, y), (x + w, y + h), color, 3)
        cv2.putText(img, f'{label} {face.score:.2f}', (x, max(y - 8, 20)), cv2.FONT_HERSHEY_SIMPLEX, 0.9, color, 2)
