package com.jon.vlmclient;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class UploadingActivity extends AppCompatActivity {
    private TextView tvUploadStatus;
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_uploading);

        tvUploadStatus = findViewById(R.id.tvUploadStatus);

        Intent intent = getIntent();
        String videoPath = intent.getStringExtra("videoPath");
        String text = intent.getStringExtra("text");

        tvUploadStatus.setText("Uploading...\nText: " + text);

        uploadVideoAndText(new File(videoPath), text);
    }

    private void uploadVideoAndText(File video, String text) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                RequestBody videoBody = RequestBody.create(MediaType.parse("video/mp4"), video);
                MultipartBody.Part videoPart = MultipartBody.Part.createFormData("video", video.getName(), videoBody);
                Request request = new Request.Builder()
                        .url("http://192.168.31.150:8000/upload")
                        .post(new MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addPart(videoPart)
                                .addFormDataPart("text", text)
                                .build())
                        .build();

                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    handler.post(() -> {
                        tvUploadStatus.append("\nServer Response: " + responseBody);
                        Toast.makeText(UploadingActivity.this, "Upload successful!", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    handler.post(() -> {
                        tvUploadStatus.append("\nError: " + response.message());
                        Toast.makeText(UploadingActivity.this, "Upload failed: " + response.message(), Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
                handler.post(() -> {
                    tvUploadStatus.append("\nError: " + e.getMessage());
                    Toast.makeText(UploadingActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
}