from blog.models import Post
from rest_framework import serializers

class PostSerializer(serializers.ModelSerializer):
    class Meta:
        model = Post
        fields = ('author', 'title', 'text', 'created_date', 'published_date', 'image')
        read_only_fields = ('author',)  # 작성자는 요청을 보낸 사용자(토큰 주인)로 서버가 지정