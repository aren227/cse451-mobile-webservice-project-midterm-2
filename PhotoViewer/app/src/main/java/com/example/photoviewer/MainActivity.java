package com.example.photoviewer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    ImageView imgView;
    TextView textView;
    //String site_url = "http://10.0.2.2:8000";                // 로컬 Django 서버 (에뮬레이터에서 PC 접속)
    static String site_url = "https://mathdong01.pythonanywhere.com";  // PythonAnywhere 클라우드 서버 (MonitorService도 사용)
    static String token = "1f4078777a1e262d5dbf3ac05cc060b96777b7d0";
    JSONObject post_json;
    String imageUrl = null;
    Bitmap bmImg = null;
    CloadImage taskDownload;
    PutPost taskUpload;

    // 업로드 대화상자에서 고른 사진
    ActivityResultLauncher<PickVisualMediaRequest> pickImage;
    Uri selectedImageUri;
    ImageView dialogPreview;

    // 감시 모드(편의 기능 10-2): 침입 알림 권한 요청 후 MonitorService 시작
    static final String EXTRA_SYNC = "sync";
    ActivityResultLauncher<String> requestNotification;
    SwitchCompat monitorSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        //imgView = (ImageView) findViewById(R.id.imgView);
        textView = (TextView) findViewById(R.id.textView);

        // 안드로이드 사진 선택기(Photo Picker): 저장공간 권한 없이 사용자가 고른 사진만 읽을 수 있음
        pickImage = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri == null || dialogPreview == null) {
                return;
            }
            try {
                dialogPreview.setImageBitmap(decodeSampledBitmap(readUriBytes(uri), 1080));
                selectedImageUri = uri;
            } catch (IOException e) {
                Toast.makeText(getApplicationContext(), "사진을 읽지 못했습니다", Toast.LENGTH_SHORT).show();
            }
        });

        requestNotification = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!granted) {
                Toast.makeText(getApplicationContext(), "알림 권한이 없어 침입 알림을 표시할 수 없습니다", Toast.LENGTH_LONG).show();
            }
            ContextCompat.startForegroundService(this, new Intent(this, MonitorService.class));
        });
        monitorSwitch = findViewById(R.id.switch_monitor);
        if (MonitorService.isRunning(this)) {  // 켜 둔 감시 모드는 앱 프로세스가 종료됐다가 다시 열려도 이어서 동작
            monitorSwitch.setChecked(true);
            ContextCompat.startForegroundService(this, new Intent(this, MonitorService.class));
        }
        monitorSwitch.setOnCheckedChangeListener((button, on) -> {
            if (!on) {
                stopService(new Intent(this, MonitorService.class));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                ContextCompat.startForegroundService(this, new Intent(this, MonitorService.class));
            }
        });
        if (getIntent().getBooleanExtra(EXTRA_SYNC, false)) {  // 침입 알림을 눌러 열었으면 바로 동기화
            startDownload();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_SYNC, false)) {
            startDownload();
        }
    }

    public void onClickDownload(View v) {
        startDownload();
        Toast.makeText(getApplicationContext(), "Download", Toast.LENGTH_LONG).show();
    }

    private void startDownload() {
        if (taskDownload != null && taskDownload.getStatus() == AsyncTask.Status.RUNNING) {
            taskDownload.cancel(true);
        }
        taskDownload = new CloadImage();
        taskDownload.execute(site_url + "/api_root/Post/");
    }

    // Upload (Post에 따른 UI 제공): Post의 image, title, text를 입력받는 대화상자
    public void onClickUpload(View v) {
        if (taskUpload != null && taskUpload.getStatus() == AsyncTask.Status.RUNNING) {
            Toast.makeText(getApplicationContext(), "업로드 중입니다", Toast.LENGTH_SHORT).show();
            return;
        }
        View view = getLayoutInflater().inflate(R.layout.dialog_upload, null);
        EditText editTitle = view.findViewById(R.id.edit_title);
        EditText editText = view.findViewById(R.id.edit_text);
        dialogPreview = view.findViewById(R.id.image_preview);
        selectedImageUri = null;
        view.findViewById(R.id.btn_pick).setOnClickListener(b -> pickImage.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("새로운 이미지 게시")
                .setView(view)
                .setPositiveButton("게시", null)
                .setNegativeButton("취소", null)
                .create();
        // 입력이 비어 있으면 대화상자를 닫지 않도록 "게시" 버튼 동작을 직접 지정
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String title = editTitle.getText().toString().trim();
            String text = editText.getText().toString().trim();
            if (selectedImageUri == null || title.isEmpty() || text.isEmpty()) {
                Toast.makeText(getApplicationContext(), "사진, 제목, 내용을 모두 입력하세요", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                byte[] image = readUriBytes(selectedImageUri);
                String fileName = queryFileName(selectedImageUri);
                String contentType = getContentResolver().getType(selectedImageUri);
                taskUpload = new PutPost(title, text, image, fileName,
                        contentType != null ? contentType : "image/jpeg");
                taskUpload.execute(site_url + "/api_root/Post/");
                textView.setText("업로드 중...");
                dialog.dismiss();
            } catch (IOException e) {
                Toast.makeText(getApplicationContext(), "사진을 읽지 못했습니다", Toast.LENGTH_SHORT).show();
            }
        }));
        dialog.show();
    }

    private class CloadImage extends AsyncTask<String, Integer, List<Bitmap>> {
        private final List<String> titleList = new ArrayList<>(); // 사진과 같은 순서로 제목을 모음

        @Override
        protected List<Bitmap> doInBackground(String... urls) {
            List<Bitmap> bitmapList = new ArrayList<>();
            try {
                String apiUrl = urls[0];
                URL urlAPI = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) urlAPI.openConnection();
                conn.setRequestProperty("Authorization", "Token " + token);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(10000); // 클라우드 서버는 한동안 요청이 없으면 첫 응답이 3초를 넘길 때가 있음

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    InputStream is = conn.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                    StringBuilder result = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.append(line);
                    }
                    is.close();

                    String strJson = result.toString();
                    JSONArray aryJson = new JSONArray(strJson);
                    // 배열 내 모든 이미지 다운로드 (서버는 오래된 글부터 주므로 뒤에서부터 담아 최신 글이 위로)
                    for (int i = aryJson.length() - 1; i >= 0; i--) {
                        post_json = (JSONObject) aryJson.get(i);
                        imageUrl = post_json.getString("image");
                        if (!imageUrl.equals("")) {
                            URL myImageUrl = new URL(imageUrl);
                            conn = (HttpURLConnection) myImageUrl.openConnection();
                            InputStream imgStream = conn.getInputStream();
                            Bitmap imageBitmap = decodeSampledBitmap(readAllBytes(imgStream), 1080);
                            bitmapList.add(imageBitmap); // 이미지 리스트에 추가
                            titleList.add(post_json.getString("title"));
                            imgStream.close();
                        }
                    }
                }
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
            return bitmapList;
        }

        @Override
        protected void onPostExecute(List<Bitmap> images) {
            if (images.isEmpty()) {
                textView.setText("불러올 이미지가 없습니다.");
            } else {
                textView.setText("이미지 로드 성공!");
                RecyclerView recyclerView = findViewById(R.id.recyclerView);
                ImageAdapter adapter = new ImageAdapter(images, titleList);
                recyclerView.setLayoutManager(new LinearLayoutManager(MainActivity.this));
                recyclerView.setAdapter(adapter);
            }
        }
    }

    // 대화상자에서 입력받은 제목, 내용, 사진을 multipart/form-data로 POST
    private class PutPost extends AsyncTask<String, Void, Integer> {
        private final String title;
        private final String text;
        private final byte[] image;
        private final String fileName;
        private final String contentType;

        PutPost(String title, String text, byte[] image, String fileName, String contentType) {
            this.title = title;
            this.text = text;
            this.image = image;
            this.fileName = fileName;
            this.contentType = contentType;
        }

        @Override
        protected Integer doInBackground(String... urls) {
            String boundary = "----PhotoViewerBoundary" + System.currentTimeMillis();
            String date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date());

            try {
                URL url = new URL(urls[0]);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Token " + token);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream out = new DataOutputStream(conn.getOutputStream());
                writeTextPart(out, boundary, "author", "1");
                writeTextPart(out, boundary, "title", title);
                writeTextPart(out, boundary, "text", text);
                writeTextPart(out, boundary, "created_date", date);
                writeTextPart(out, boundary, "published_date", date);
                writeFilePart(out, boundary, "image", fileName, contentType, image);
                out.writeBytes("--" + boundary + "--\r\n");
                out.flush();
                out.close();

                int responseCode = conn.getResponseCode();
                conn.disconnect();
                return responseCode;
            } catch (IOException e) {
                e.printStackTrace();
                return -1;
            }
        }

        @Override
        protected void onPostExecute(Integer responseCode) {
            if (responseCode == HttpURLConnection.HTTP_CREATED) {
                textView.setText("업로드 성공! (HTTP 201)");
                Toast.makeText(getApplicationContext(), "업로드 성공 (HTTP 201)", Toast.LENGTH_LONG).show();
                startDownload(); // 새 글이 보이도록 목록을 다시 불러옴
            } else {
                textView.setText("업로드 실패 (응답 코드: " + responseCode + ")");
                Toast.makeText(getApplicationContext(), "업로드 실패", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static void writeTextPart(DataOutputStream out, String boundary, String name, String value)
            throws IOException {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n");
        out.writeBytes("\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8)); // 한글 제목·본문은 UTF-8로 전송
        out.writeBytes("\r\n");
    }

    private static void writeFilePart(DataOutputStream out, String boundary, String name,
                                      String fileName, String contentType, byte[] data) throws IOException {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n");
        out.writeBytes("Content-Type: " + contentType + "\r\n");
        out.writeBytes("\r\n");
        out.write(data);
        out.writeBytes("\r\n");
    }

    private byte[] readUriBytes(Uri uri) throws IOException {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IOException("열 수 없는 사진: " + uri);
            }
            return readAllBytes(is);
        }
    }

    private String queryFileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        }
        return "upload.jpg";
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    // 큰 사진(예: 4000x3000)을 원본 크기로 읽으면 한 장에 수십 MB라 메모리가 부족해지므로 화면 폭에 맞게 줄여서 읽음
    private static Bitmap decodeSampledBitmap(byte[] data, int reqWidth) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);

        int inSampleSize = 1;
        while (options.outWidth / (inSampleSize * 2) >= reqWidth) {
            inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = inSampleSize;
        return rotateByExif(data, BitmapFactory.decodeByteArray(data, 0, data.length, options));
    }

    // 스마트폰 사진은 회전 정보(EXIF)만 기록해 두는 경우가 많아, BitmapFactory로 읽으면 옆으로 누워 보이므로 바로 세움
    private static Bitmap rotateByExif(byte[] data, Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        int degrees;
        try (InputStream is = new ByteArrayInputStream(data)) {
            ExifInterface exif = new ExifInterface(is);
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    degrees = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    degrees = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    degrees = 270;
                    break;
                default:
                    return bitmap;
            }
        } catch (IOException e) {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }
}
