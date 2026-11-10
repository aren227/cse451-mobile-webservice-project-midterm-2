package com.example.photoviewer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 편의 기능 10-2: 감시 모드. 앱이 꺼져 있어도 10초마다 서버를 폴링해서
 * YOLO 엣지가 새로 올린 게시물(침입 감지)이 있으면 사진이 들어간 알림을 띄운다.
 */
public class MonitorService extends Service {
    static final String PREFS = "monitor";
    private static final String TAG = "MonitorService";
    private static final String CHANNEL_MONITOR = "monitor";   // 감시 중 상태 표시 (조용히)
    private static final String CHANNEL_ALERT = "intrusion";   // 침입 알림 (소리·헤드업)
    private static final int ID_MONITOR = 1;
    private static final long INTERVAL_SEC = 10;

    private ScheduledExecutorService scheduler;
    private SharedPreferences prefs;

    static void setRunning(Context context, boolean running) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("running", running).apply();
    }

    static boolean isRunning(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("running", false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_MONITOR, "감시 모드", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT, "침입 알림", NotificationManager.IMPORTANCE_HIGH));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification ongoing = new NotificationCompat.Builder(this, CHANNEL_MONITOR)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("감시 모드 동작 중")
                .setContentText(INTERVAL_SEC + "초마다 서버의 새 침입 기록을 확인합니다")
                .setContentIntent(openAppIntent(false))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_MONITOR, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_MONITOR, ongoing);
        }
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleWithFixedDelay(this::poll, 0, INTERVAL_SEC, TimeUnit.SECONDS);
        }
        setRunning(this, true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        setRunning(this, false);
        super.onDestroy();
    }

    // Android 15+: dataSync 포그라운드 서비스는 하루 실행 시간 제한이 있어, 제한에 걸리면 스스로 종료해야 함
    @Override
    public void onTimeout(int startId, int fgsType) {
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // 서버의 게시물 목록을 받아 처음 보는 게시물(이미지 주소 기준)만 알림
    private void poll() {
        try {
            JSONArray posts = new JSONArray(new String(get(MainActivity.site_url + "/api_root/Post/"), StandardCharsets.UTF_8));
            boolean firstRun = !prefs.contains("seen");  // 처음 켰을 때는 기존 게시물을 기준으로만 저장
            Set<String> seen = new HashSet<>(prefs.getStringSet("seen", new HashSet<>()));
            for (int i = 0; i < posts.length(); i++) {
                JSONObject post = posts.getJSONObject(i);
                String image = post.getString("image");
                if (seen.add(image) && !firstRun) {
                    notifyIntrusion(post.getString("title"), post.getString("text"), image);
                }
            }
            prefs.edit().putStringSet("seen", seen).apply();
        } catch (Exception e) {
            Log.w(TAG, "poll failed", e);
        }
    }

    private void notifyIntrusion(String title, String text, String imageUrl) {
        Bitmap picture = null;
        try {
            byte[] bytes = get(imageUrl);
            picture = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IOException e) {
            Log.w(TAG, "image download failed", e);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("침입 감지: " + title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(openAppIntent(true))
                .setAutoCancel(true);
        if (picture != null) {
            builder.setLargeIcon(picture)
                    .setStyle(new NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon((Bitmap) null));
        }
        getSystemService(NotificationManager.class).notify(imageUrl.hashCode(), builder.build());
    }

    // 알림을 누르면 앱을 열고 바로 동기화
    private PendingIntent openAppIntent(boolean sync) {
        Intent intent = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_SYNC, sync)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, sync ? 1 : 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("Authorization", "Token " + MainActivity.token);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }
}
