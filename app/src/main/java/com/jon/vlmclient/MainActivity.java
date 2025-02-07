package com.jon.vlmclient;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SPEECH_INPUT = 1;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private TextureView textureView;
    private MediaRecorder mediaRecorder;
    private Button btnSpeak;
    private TextView tvResult;
    private File videoFile;
    private boolean isRecording = false;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private Surface previewSurface;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private TextToSpeech textToSpeech; // Added TextToSpeech instance

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize views
        textureView = findViewById(R.id.textureView);
        btnSpeak = findViewById(R.id.btnSpeak);
        tvResult = findViewById(R.id.tvResult);

        // Initialize TextToSpeech
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(Locale.US);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(MainActivity.this, "Language not supported", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(MainActivity.this, "TextToSpeech initialization failed", Toast.LENGTH_SHORT).show();
            }
        });

        // Check permissions before proceeding
        if (!checkPermissions()) {
            requestPermissions();
        }

        // Set up TextureView listener
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                startCameraPreview();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
        });

        // Handle button clicks
        btnSpeak.setOnClickListener(view -> {
            try {
                if (!isRecording) {
                    startRecording();
                    btnSpeak.setText("Stop Recording");
                } else {
                    stopRecording();
                    startSpeechToText();
                    btnSpeak.setText("Processing...");
                    btnSpeak.setEnabled(false); // Disable button during processing
                }
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(MainActivity.this, "An error occurred: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (textureView.isAvailable()) {
            startCameraPreview();
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                PERMISSION_REQUEST_CODE);
    }

    private void startCameraPreview(Surface... additionalSurfaces) {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String cameraId = cameraManager.getCameraIdList()[0]; // Using front camera (index 0)
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCameraPreviewSession(additionalSurfaces);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    Toast.makeText(MainActivity.this, "Camera error: " + error, Toast.LENGTH_SHORT).show();
                }
            }, backgroundHandler);
        } catch (CameraAccessException | SecurityException e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to access camera: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void createCameraPreviewSession(Surface... additionalSurfaces) {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(1080, 1920);
            previewSurface = new Surface(texture);

            // Create a list of surfaces to include in the session
            ArrayList<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface); // Add the preview surface
            if (additionalSurfaces != null) {
                for (Surface surface : additionalSurfaces) {
                    surfaces.add(surface); // Add any additional surfaces (e.g., recorderSurface)
                }
            }

            final CaptureRequest.Builder previewRequestBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            for (Surface surface : surfaces) {
                previewRequestBuilder.addTarget(surface); // Add each surface as a target
            }

            cameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            cameraCaptureSession = session;
                            try {
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                                session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(MainActivity.this, "Failed to start camera preview", Toast.LENGTH_SHORT).show();
                        }
                    }, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void startRecording() {
        if (isRecording) return;
        videoFile = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "recorded_video.mp4");
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        // Use CamcorderProfile for proper encoding settings
        CamcorderProfile profile = CamcorderProfile.get(CamcorderProfile.QUALITY_1080P);
        mediaRecorder.setOutputFormat(profile.fileFormat);
        mediaRecorder.setOutputFile(videoFile.getAbsolutePath());
        mediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
        mediaRecorder.setVideoFrameRate(profile.videoFrameRate);
        mediaRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        mediaRecorder.setVideoEncoder(profile.videoCodec);
        mediaRecorder.setAudioEncoder(profile.audioCodec);
        mediaRecorder.setAudioEncodingBitRate(profile.audioBitRate);
        mediaRecorder.setAudioSamplingRate(profile.audioSampleRate);

        try {
            mediaRecorder.prepare();
            Surface recorderSurface = mediaRecorder.getSurface();

            // Close the current camera session and restart preview with recording
            closeCamera();
            startCameraPreview(recorderSurface);
            mediaRecorder.start();
            isRecording = true;
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error starting video recording", Toast.LENGTH_SHORT).show();
            mediaRecorder.release();
            mediaRecorder = null;
            isRecording = false;
        }
    }

    private void stopRecording() {
        if (isRecording) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
                Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) {
                e.printStackTrace();
                Toast.makeText(this, "Error stopping video recording", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startSpeechToText() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...");
        try {
            startActivityForResult(intent, REQUEST_CODE_SPEECH_INPUT);
        } catch (Exception e) {
            Toast.makeText(this, "Speech recognition not supported", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultCode == RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty()) {
                String spokenText = result.get(0);
                tvResult.setText(spokenText);
                sendVideoAndTextToServer(videoFile, spokenText);
            }
        }
    }

    private void sendVideoAndTextToServer(File video, String text) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                RequestBody videoBody = RequestBody.create(MediaType.parse("video/mp4"), video);
                MultipartBody.Part videoPart = MultipartBody.Part.createFormData("video", video.getName(), videoBody);
                Request request = new Request.Builder()
                        .url("http://192.168.31.150:8000/upload")  // Replace with your server URL
                        .post(new MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addPart(videoPart)
                                .addFormDataPart("text", text)
                                .build())
                        .build();
                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    runOnUiThread(() -> {
                        tvResult.append("\nServer: " + responseBody);
                        Toast.makeText(MainActivity.this, "Upload successful!", Toast.LENGTH_SHORT).show();
                        // Enable the button again and reset its text
                        btnSpeak.setText("Record");
                        btnSpeak.setEnabled(true);

                        // Read out the server response using TextToSpeech
                        textToSpeech.speak(responseBody, TextToSpeech.QUEUE_FLUSH, null, null);
                    });
                } else {
                    runOnUiThread(() -> {
                        tvResult.append("\nError: " + response.message());
                        Toast.makeText(MainActivity.this, "Upload failed: " + response.message(), Toast.LENGTH_SHORT).show();
                        // Enable the button again and reset its text
                        btnSpeak.setText("Record");
                        btnSpeak.setEnabled(true);
                    });
                }
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    tvResult.append("\nError: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    // Enable the button again and reset its text
                    btnSpeak.setText("Record");
                    btnSpeak.setEnabled(true);
                });
            }
        }).start();
    }
}