package com.example.photoviewer;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ImageAdapter extends RecyclerView.Adapter<ImageAdapter.ImageViewHolder> {
    private List<Bitmap> imageList;
    private List<String> titleList; // 사진과 같은 순서의 게시물 제목

    public ImageAdapter(List<Bitmap> imageList, List<String> titleList) { // 생성자에서 이미지, 제목 목록 입력
        this.imageList = imageList;
        this.titleList = titleList;
    }

    @NonNull
    @Override
    public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // 이미지 항목을 나타낼 뷰 생성
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_image, parent, false);
        return new ImageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
        // 해당 위치의 이미지를 뷰에 설정
        Bitmap bitmap = imageList.get(position);
        holder.imageView.setImageBitmap(bitmap);
        holder.titleView.setText(titleList.get(position));
    }

    @Override
    public int getItemCount() {
        return imageList.size();
    }

    public static class ImageViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        TextView titleView;

        public ImageViewHolder(View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.imageViewItem); // item_image.xml에 있는 ImageView
            titleView = itemView.findViewById(R.id.textViewTitle);
        }
    }
}
