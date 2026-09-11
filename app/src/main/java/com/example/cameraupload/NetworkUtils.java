package com.example.cameraupload;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class NetworkUtils {
    private static final String TAG = "NetworkUtils";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType AUDIO_WAV = MediaType.get("audio/wav");

    private static final String SERVER_BASE_URL = "请替换成您的服务器地址";
    private static final String DETECTION_UPLOAD_URL = SERVER_BASE_URL + "/upload-results";
    private static final String HELP_LOCATION_URL = SERVER_BASE_URL + "/help-request";
    private static final String HELP_VOICE_UPLOAD_URL = SERVER_BASE_URL + "/help-voice";

    private static final String SAFE_TOKEN = "请替换成您的密钥";

    private static final int CONNECT_TIMEOUT_SECONDS = 15;
    private static final int WRITE_TIMEOUT_SECONDS = 15;
    private static final int READ_TIMEOUT_SECONDS = 30;

    private static final Handler MAIN_THREAD_HANDLER = new Handler(Looper.getMainLooper());

    private final OkHttpClient client;
    private static volatile NetworkUtils instance;

    private NetworkUtils() {
        client = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public static NetworkUtils getInstance() {
        if (instance == null) {
            synchronized (NetworkUtils.class) {
                if (instance == null) {
                    instance = new NetworkUtils();
                }
            }
        }
        return instance;
    }

    // ====================== 回调接口 ======================
    public interface DetectionCallback {
        void onSuccess(String response);
        void onFailure(String error);
    }

    public interface HelpLocationCallback {
        void onSuccess(String response);
        void onFailure(String error);
    }

    public interface VoiceUploadCallback {
        void onSuccess(String response);
        void onFailure(String error);
    }

    public void sendDetectionResults(List<DetectionResult> results, String imageSize, DetectionCallback callback) {
        if (results == null || results.isEmpty()) {
            notifyFailure(callback, "没有检测结果");
            return;
        }

        if (imageSize == null || imageSize.trim().isEmpty()) {
            notifyFailure(callback, "图像尺寸不能为空");
            return;
        }

        try {
            JSONObject requestData = new JSONObject();
            requestData.put("timestamp", System.currentTimeMillis());
            requestData.put("image_size", imageSize.trim());
            requestData.put("device_id", Build.MODEL);
            requestData.put("android_version", Build.VERSION.RELEASE);

            JSONArray detectionsArray = new JSONArray();
            for (DetectionResult result : results) {
                if (result == null) continue;
                JSONObject detection = new JSONObject();
                detection.put("label", result.getLabel() == null ? "" : result.getLabel());
                detection.put("confidence", result.getConfidence());
                JSONArray bboxArray = new JSONArray();
                bboxArray.put(Math.max(0, result.getLeft()));
                bboxArray.put(Math.max(0, result.getTop()));
                bboxArray.put(Math.max(0, result.getRight()));
                bboxArray.put(Math.max(0, result.getBottom()));
                detection.put("bbox", bboxArray);
                detectionsArray.put(detection);
            }
            requestData.put("detections", detectionsArray);

            String requestJson = requestData.toString();
            Log.d(TAG, "发送检测数据 -> " + requestJson);
            RequestBody body = RequestBody.create(requestJson, JSON);

            Request request = new Request.Builder()
                    .url(DETECTION_UPLOAD_URL)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "CameraUpload/" + Build.MODEL)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    notifyFailure(callback, "发送失败: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response res = response) {
                        if (res.isSuccessful()) {
                            String body = res.body() != null ? res.body().string() : "";
                            notifySuccess(callback, body);
                        } else {
                            notifyFailure(callback, "HTTP " + res.code());
                        }
                    } catch (Exception e) {
                        notifyFailure(callback, "响应处理异常: " + e.getMessage());
                    }
                }
            });

        } catch (JSONException e) {
            notifyFailure(callback, "JSON构建错误: " + e.getMessage());
        }
    }

    // 发送求助定位
    public void sendHelpLocation(double latitude, double longitude, String address, String city,
                                 HelpLocationCallback callback) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "help_request");
            json.put("wake_word", "小瞳小瞳，我需要帮助");
            json.put("timestamp", System.currentTimeMillis());
            json.put("device_id", Build.MODEL);
            json.put("token", SAFE_TOKEN);

            JSONObject location = new JSONObject();
            location.put("latitude", latitude);
            location.put("longitude", longitude);
            location.put("address", address != null ? address : "");
            location.put("city", city != null ? city : "");
            json.put("location", location);

            String requestJson = json.toString();
            Log.d(TAG, "发送求助定位 -> " + requestJson);
            RequestBody body = RequestBody.create(requestJson, JSON);

            Request request = new Request.Builder()
                    .url(HELP_LOCATION_URL)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    notifyHelpLocationFailure(callback, "网络异常: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response res = response) {
                        if (res.isSuccessful()) {
                            String body = res.body() != null ? res.body().string() : "";
                            notifyHelpLocationSuccess(callback, body);
                        } else {
                            notifyHelpLocationFailure(callback, "HTTP " + res.code());
                        }
                    } catch (Exception e) {
                        notifyHelpLocationFailure(callback, "响应异常: " + e.getMessage());
                    }
                }
            });

        } catch (JSONException e) {
            notifyHelpLocationFailure(callback, "JSON构建错误: " + e.getMessage());
        }
    }

    //上传求助语音留言
    public void uploadHelpVoice(File audioFile, String transcript, VoiceUploadCallback callback) {
        if (audioFile == null || !audioFile.exists()) {
            notifyVoiceUploadFailure(callback, "音频文件不存在");
            return;
        }

        RequestBody fileBody = RequestBody.create(audioFile, AUDIO_WAV);  // 使用 WAV 类型
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.getName(), fileBody)
                .addFormDataPart("device_id", Build.MODEL)
                .addFormDataPart("timestamp", String.valueOf(System.currentTimeMillis()))
                .addFormDataPart("token", SAFE_TOKEN);

        if (transcript != null && !transcript.isEmpty()) {
            builder.addFormDataPart("transcript", transcript);
        }

        MultipartBody requestBody = builder.build();

        Request request = new Request.Builder()
                .url(HELP_VOICE_UPLOAD_URL)
                .post(requestBody)
                .build();

        Log.d(TAG, "上传语音留言: " + audioFile.getName() + ", 转写文字: " + transcript);

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                notifyVoiceUploadFailure(callback, "上传失败: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response res = response) {
                    if (res.isSuccessful()) {
                        String body = res.body() != null ? res.body().string() : "";
                        notifyVoiceUploadSuccess(callback, body);
                    } else {
                        notifyVoiceUploadFailure(callback, "HTTP " + res.code());
                    }
                } catch (Exception e) {
                    notifyVoiceUploadFailure(callback, "响应异常: " + e.getMessage());
                }
            }
        });
    }

    //辅助回调方法
    private void notifySuccess(DetectionCallback callback, String response) {
        if (callback != null) {
            MAIN_THREAD_HANDLER.post(() -> callback.onSuccess(response));
        }
    }

    private void notifyFailure(DetectionCallback callback, String error) {
        if (callback != null) {
            MAIN_THREAD_HANDLER.post(() -> callback.onFailure(error));
        }
    }

    private void notifyHelpLocationSuccess(HelpLocationCallback callback, String response) {
        if (callback != null) {
            MAIN_THREAD_HANDLER.post(() -> callback.onSuccess(response));
        }
    }

    private void notifyHelpLocationFailure(HelpLocationCallback callback, String error) {
        if (callback != null) {
            MAIN_THREAD_HANDLER.post(() -> callback.onFailure(error));
        }
    }

    private void notifyVoiceUploadSuccess(VoiceUploadCallback callback, String response) {
        if (callback != null) {
            MAIN_THREAD_HANDLER.post(() -> callback.onSuccess(response));
        }
    }

    private void notifyVoiceUploadFailure(VoiceUploadCallback callback, String error) {
        if (callback != null) {
            MAIN_THREAD_HANDLER.post(() -> callback.onFailure(error));
        }
    }

    //清理资源
    public void shutdown() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}