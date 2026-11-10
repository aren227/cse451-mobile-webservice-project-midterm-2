import shutil
import tempfile
from datetime import timedelta
from io import BytesIO

from django.contrib.auth.models import User
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase, override_settings
from django.urls import reverse
from django.utils import timezone
from PIL import Image
from rest_framework import status
from rest_framework.authtoken.models import Token
from rest_framework.test import APIClient

from .models import Post

# 업로드 테스트가 실제 media/ 폴더를 건드리지 않도록 임시 폴더를 사용
TEMP_MEDIA_ROOT = tempfile.mkdtemp()

API_LIST_URL = '/api_root/Post/'
POST_FIELDS = {'author', 'title', 'text', 'created_date', 'published_date', 'image'}


def tearDownModule():
    shutil.rmtree(TEMP_MEDIA_ROOT, ignore_errors=True)


def make_image(name='test.png'):
    """Pillow로 만든 작은 PNG 이미지"""
    buffer = BytesIO()
    Image.new('RGB', (10, 10), 'orange').save(buffer, 'PNG')
    return SimpleUploadedFile(name, buffer.getvalue(), content_type='image/png')


class PostModelTest(TestCase):
    def setUp(self):
        self.user = User.objects.create_user('tester', password='pass1234!')

    def test_str_returns_title(self):
        post = Post(author=self.user, title='서진이', text='그림을 그린다')
        self.assertEqual(str(post), '서진이')

    def test_publish_sets_published_date_and_saves(self):
        post = Post.objects.create(author=self.user, title='draft', text='text')
        self.assertIsNone(post.published_date)

        post.publish()

        post.refresh_from_db()
        self.assertIsNotNone(post.published_date)
        self.assertLessEqual(post.published_date, timezone.now())

    def test_default_image_is_used_when_not_given(self):
        post = Post.objects.create(author=self.user, title='no image', text='text')
        self.assertEqual(post.image.name, 'blog_image/default_error.png')


class PostWebViewTest(TestCase):
    def setUp(self):
        self.user = User.objects.create_user('tester', password='pass1234!')
        now = timezone.now()
        self.old_post = Post.objects.create(
            author=self.user, title='old post', text='old', published_date=now - timedelta(days=2))
        self.new_post = Post.objects.create(
            author=self.user, title='new post', text='new', published_date=now - timedelta(days=1))
        self.draft_post = Post.objects.create(
            author=self.user, title='draft post', text='draft')
        self.future_post = Post.objects.create(
            author=self.user, title='future post', text='future', published_date=now + timedelta(days=1))

    def test_post_list_shows_only_published_posts(self):
        response = self.client.get(reverse('post_list'))

        self.assertEqual(response.status_code, 200)
        self.assertTemplateUsed(response, 'blog/post_list.html')
        self.assertContains(response, 'old post')
        self.assertContains(response, 'new post')
        self.assertNotContains(response, 'draft post')
        self.assertNotContains(response, 'future post')

    def test_post_list_is_ordered_by_published_date(self):
        response = self.client.get(reverse('post_list'))
        self.assertEqual(list(response.context['posts']), [self.old_post, self.new_post])

    def test_post_detail(self):
        response = self.client.get(reverse('post_detail', kwargs={'pk': self.old_post.pk}))

        self.assertEqual(response.status_code, 200)
        self.assertTemplateUsed(response, 'blog/post_detail.html')
        self.assertContains(response, 'old post')

    def test_post_detail_returns_404_for_unknown_post(self):
        response = self.client.get(reverse('post_detail', kwargs={'pk': 9999}))
        self.assertEqual(response.status_code, 404)

    def test_post_new_get_shows_form(self):
        self.client.force_login(self.user)
        response = self.client.get(reverse('post_new'))

        self.assertEqual(response.status_code, 200)
        self.assertTemplateUsed(response, 'blog/post_edit.html')

    def test_post_new_post_creates_published_post(self):
        self.client.force_login(self.user)
        response = self.client.post(reverse('post_new'), {'title': 'web post', 'text': 'from form'})

        post = Post.objects.get(title='web post')
        self.assertRedirects(response, reverse('post_detail', kwargs={'pk': post.pk}))
        self.assertEqual(post.author, self.user)
        self.assertIsNotNone(post.published_date)

    def test_post_new_with_invalid_form_shows_form_again(self):
        self.client.force_login(self.user)
        response = self.client.post(reverse('post_new'), {'title': '', 'text': ''})

        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.context['form'].errors)
        self.assertEqual(Post.objects.count(), 4)

    def test_post_edit_get_shows_existing_post(self):
        self.client.force_login(self.user)
        response = self.client.get(reverse('post_edit', kwargs={'pk': self.old_post.pk}))

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.context['form'].instance, self.old_post)

    def test_post_edit_post_updates_post(self):
        self.client.force_login(self.user)
        url = reverse('post_edit', kwargs={'pk': self.old_post.pk})
        response = self.client.post(url, {'title': 'edited', 'text': 'edited text'})

        self.assertRedirects(response, reverse('post_detail', kwargs={'pk': self.old_post.pk}))
        self.old_post.refresh_from_db()
        self.assertEqual(self.old_post.title, 'edited')

    def test_post_list_shows_text(self):
        response = self.client.get(reverse('post_list'))
        self.assertContains(response, '<p>old</p>', html=True)

    def test_js_test_page(self):
        response = self.client.get(reverse('js_test'))

        self.assertEqual(response.status_code, 200)
        self.assertTemplateUsed(response, 'blog/js_test.html')


@override_settings(MEDIA_ROOT=TEMP_MEDIA_ROOT)
class PostApiTest(TestCase):
    """ch05 7~10p에서 브라우저, telnet, codebeautify로 손으로 확인한 내용을 자동으로 검증"""

    def setUp(self):
        self.user = User.objects.create_user('tester', password='pass1234!')
        self.token = Token.objects.create(user=self.user)
        self.client = APIClient()
        # 안드로이드 앱(ch05 21p)처럼 토큰 헤더를 붙여서 요청
        self.client.credentials(HTTP_AUTHORIZATION='Token ' + self.token.key)

    def create_post(self, title='API post'):
        return Post.objects.create(
            author=self.user, title=title, text='text',
            published_date=timezone.now(), image=make_image())

    def post_data(self, **overrides):
        data = {
            'author': self.user.pk,
            'title': '제목 by test',
            'text': 'API 내용 by test',
            'created_date': '2026-10-19T18:00:00+09:00',
            'published_date': '2026-10-19T18:00:00+09:00',
            'image': make_image(),
        }
        data.update(overrides)
        return data

    def test_api_root_lists_post_endpoint(self):
        response = self.client.get('/api_root/')

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Post', response.json())

    def test_list_allows_get_and_post(self):
        response = self.client.get(API_LIST_URL)

        for method in ('GET', 'POST', 'HEAD', 'OPTIONS'):
            self.assertIn(method, response['Allow'])

    def test_list_returns_json_array_with_post_fields(self):
        self.create_post('first')
        self.create_post('second')

        response = self.client.get(API_LIST_URL)

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response['Content-Type'], 'application/json')
        body = response.json()
        self.assertIsInstance(body, list)
        self.assertEqual(len(body), 2)
        for item in body:
            self.assertEqual(set(item), POST_FIELDS)

    def test_list_image_is_absolute_url(self):
        self.create_post()

        image_url = self.client.get(API_LIST_URL).json()[0]['image']

        self.assertTrue(image_url.startswith('http://testserver/media/blog_image/'))

    def test_request_like_android_emulator(self):
        # ch05 8p 요청과 같은 헤더로 보내면 이미지 URL도 에뮬레이터가 접근할 수 있는 주소로 나옴
        self.create_post()

        response = self.client.get(
            API_LIST_URL,
            HTTP_HOST='10.0.2.2:8000',
            HTTP_USER_AGENT='Dalvik/2.1.0 (Linux; U; Android 13; sdk_gphone64_arm64 Build/TPB4.220624.004)',
        )

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertTrue(response.json()[0]['image'].startswith('http://10.0.2.2:8000/media/'))

    def test_unknown_host_is_rejected(self):
        response = self.client.get(API_LIST_URL, HTTP_HOST='unknown.example.com')
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)

    def test_create_post_with_image_by_multipart(self):
        response = self.client.post(API_LIST_URL, self.post_data(), format='multipart')

        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(response.json()['title'], '제목 by test')
        post = Post.objects.get()
        self.assertEqual(post.author, self.user)
        self.assertRegex(post.image.name, r'^blog_image/\d{4}/\d{2}/\d{2}/test.*\.png$')
        self.assertTrue(post.image.storage.exists(post.image.name))

    def test_create_post_without_title_is_rejected(self):
        response = self.client.post(API_LIST_URL, self.post_data(title=''), format='multipart')

        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('title', response.json())
        self.assertFalse(Post.objects.exists())

    def test_create_post_with_non_image_file_is_rejected(self):
        not_image = SimpleUploadedFile('note.txt', b'not an image', content_type='text/plain')
        response = self.client.post(API_LIST_URL, self.post_data(image=not_image), format='multipart')

        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('image', response.json())

    def test_retrieve_post(self):
        post = self.create_post('detail post')

        response = self.client.get(f'{API_LIST_URL}{post.pk}/')

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.json()['title'], 'detail post')

    def test_retrieve_unknown_post_returns_404(self):
        response = self.client.get(f'{API_LIST_URL}9999/')
        self.assertEqual(response.status_code, status.HTTP_404_NOT_FOUND)

    def test_partial_update_post(self):
        post = self.create_post()

        response = self.client.patch(f'{API_LIST_URL}{post.pk}/', {'title': 'patched'}, format='json')

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        post.refresh_from_db()
        self.assertEqual(post.title, 'patched')

    def test_delete_post(self):
        post = self.create_post()

        response = self.client.delete(f'{API_LIST_URL}{post.pk}/')

        self.assertEqual(response.status_code, status.HTTP_204_NO_CONTENT)
        self.assertFalse(Post.objects.exists())


class TokenAuthApiTest(TestCase):
    """ch03 34~38p: 아이디/비밀번호로 토큰을 발급받는 API"""

    def setUp(self):
        self.user = User.objects.create_user('tester', password='pass1234!')

    def test_obtain_token_with_valid_credentials(self):
        response = APIClient().post('/api-token-auth/', {'username': 'tester', 'password': 'pass1234!'})

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.json()['token'], Token.objects.get(user=self.user).key)

    def test_obtain_token_with_wrong_password_is_rejected(self):
        response = APIClient().post('/api-token-auth/', {'username': 'tester', 'password': 'wrong'})
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)


@override_settings(MEDIA_ROOT=TEMP_MEDIA_ROOT)
class ChangeDetectionApiTest(TestCase):
    """ch08 8~10p: YOLO 엣지 클라이언트(changedetection.py)가 보내는 요청"""

    def setUp(self):
        self.user = User.objects.create_user('edge', password='pass1234!')
        self.other = User.objects.create_user('other', password='pass1234!')
        self.token = Token.objects.create(user=self.user)

    def edge_data(self, **overrides):
        # changedetection.py와 같이 author 없이 title, text, 날짜(datetime을 str로 보낸 형태), 이미지만 보냄
        data = {
            'title': 'cell phone',
            'text': 'person, cell phone, ',
            'created_date': '2026-11-10 02:10:11.123456',
            'published_date': '2026-11-10 02:10:11.123456',
            'image': make_image('2-10-11-123456.jpg'),
        }
        data.update(overrides)
        return data

    def test_token_post_without_author_sets_author_to_token_user(self):
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION='Token ' + self.token.key)

        response = client.post(API_LIST_URL, self.edge_data(), format='multipart')

        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        post = Post.objects.get()
        self.assertEqual(post.author, self.user)
        self.assertEqual(post.text, 'person, cell phone,')  # DRF CharField가 끝 공백을 제거 (강의 12p와 동일)
        # 시간대 없는 시각은 서버 TIME_ZONE(Asia/Seoul) 기준으로 저장
        self.assertEqual(timezone.localtime(post.published_date).hour, 2)

    def test_author_in_request_is_ignored(self):
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION='Token ' + self.token.key)

        client.post(API_LIST_URL, self.edge_data(author=self.other.pk), format='multipart')

        self.assertEqual(Post.objects.get().author, self.user)

    def test_post_without_token_is_rejected(self):
        response = APIClient().post(API_LIST_URL, self.edge_data(), format='multipart')

        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
        self.assertFalse(Post.objects.exists())

    def test_lecture_jwt_header_is_not_accepted(self):
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION='JWT ' + self.token.key)

        response = client.post(API_LIST_URL, self.edge_data(), format='multipart')

        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)

    def test_invalid_token_is_rejected(self):
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION='Token invalid')

        response = client.get(API_LIST_URL)

        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)

    def test_anonymous_can_read_list(self):
        response = APIClient().get(API_LIST_URL)
        self.assertEqual(response.status_code, status.HTTP_200_OK)

    def test_anonymous_cannot_delete(self):
        post = Post.objects.create(author=self.user, title='t', text='t', published_date=timezone.now())

        response = APIClient().delete(f'{API_LIST_URL}{post.pk}/')

        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
        self.assertTrue(Post.objects.exists())
