package com.example.cameraupload;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.amap.api.maps.MapsInitializer;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "YOLO_RealTime";

    private static final int REQUEST_PERMISSIONS = 100;
    private static final int REQUEST_BACKGROUND_LOCATION = 101;

    private static final float CONFIDENCE_THRESHOLD = 0.6f;
    private static final float IOU_THRESHOLD = 0.5f;
    private static final int INPUT_SIZE = 640;
    private static final long SEND_INTERVAL = 1000;

    private static final long[] VIBRATE_PATTERN = {0, 100, 50, 100, 50, 100};
    private static final float FOCAL_LENGTH_PX = 1000f;

    private static final Map<String, Float> OBJECT_REAL_WIDTH_CM = new HashMap<String, Float>() {{
        put("树木", 80f);
        put("红灯", 30f);
        put("绿灯", 30f);
        put("斑马线", 300f);
        put("盲道", 60f);
        put("交通标识", 40f);
        put("行人", 50f);
        put("自行车", 60f);
        put("公交车", 250f);
        put("卡车", 200f);
        put("汽车", 180f);
        put("摩托车", 80f);
        put("反光锥", 30f);
        put("垃圾桶", 40f);
        put("警示柱", 20f);
        put("路障", 50f);
        put("电线杆", 30f);
        put("狗", 30f);
        put("三轮车", 80f);
        put("消防栓", 40f);
    }};

    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView statusText;
    private TextView fpsText;

    private Interpreter tflite;
    private List<String> labels;
    private ExecutorService analysisExecutor;
    private long lastAnalysisTime = 0;
    private int frameCount = 0;
    private float currentFPS = 0;
    private long lastSendTime = 0;

    private TextToSpeech tts;
    private Vibrator vibrator;
    private static final long SPEAK_COOLDOWN = 6000;
    private long lastSpeakTime = 0;
    private String lastSpokenText = "";
    private int previewWidth;
    private int previewHeight;

    private NetworkUtils networkUtils;
    private VoiceNaviHelper voiceNaviHelper;
    private String currentLocation = "未知位置";
    private boolean isNavigating = false;

    private static final String PREF_NAME = "app_prefs";
    private static final String KEY_PRIVACY_AGREED = "privacy_agreed";

    private List<DetectionResult> currentDetectionResults = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        checkPrivacyAgreement();
    }

    private void checkPrivacyAgreement() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean agreed = prefs.getBoolean(KEY_PRIVACY_AGREED, false);
        if (agreed) {
            initAfterPrivacyAgreed();
        } else {
            showPrivacyDialog();
        }
    }

    private void showPrivacyDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("隐私政策");
        builder.setMessage("本应用需要使用高德地图定位导航服务，会收集您的位置信息以提供导航功能。\n\n" +
                "我们将严格遵守相关法律法规，保护您的个人隐私。详细内容请阅读《隐私政策》。\n\n" +
                "是否同意？");
        builder.setPositiveButton("同意", (dialog, which) -> {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_PRIVACY_AGREED, true).apply();
            initAMapPrivacy();
            initAfterPrivacyAgreed();
        });
        builder.setNegativeButton("拒绝", (dialog, which) -> {
            Toast.makeText(MainActivity.this, "您已拒绝隐私政策，部分功能将无法使用", Toast.LENGTH_LONG).show();
            finish();
        });
        builder.setCancelable(false);
        builder.show();
    }

    private void initAfterPrivacyAgreed() {
        initializeViews();
        initializeNetworkUtils();
        checkPermissions();
    }

    private void initAMapPrivacy() {
        try {
            MapsInitializer.updatePrivacyShow(this, true, true);
            MapsInitializer.updatePrivacyAgree(this, true);
            Log.d(TAG, "✅ 高德隐私合规设置完成");
        } catch (Exception e) {
            Log.e(TAG, "❌ 高德隐私合规设置失败", e);
            updateStatus("高德隐私合规初始化失败：" + e.getMessage());
        }
    }

    private void initializeViews() {
        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        statusText = findViewById(R.id.statusText);
        fpsText = findViewById(R.id.fpsText);
        statusText.setText("初始化中...");
        fpsText.setText("FPS: 0");
        analysisExecutor = Executors.newSingleThreadExecutor();
    }

    private void initializeNetworkUtils() {
        networkUtils = NetworkUtils.getInstance();
        Log.d(TAG, "网络工具类初始化完成");
    }

    private void checkPermissions() {
        List<String> missingPermissions = new ArrayList<>();
        String[] normalPermissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.VIBRATE,
                Manifest.permission.RECORD_AUDIO
        };
        for (String perm : normalPermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(perm);
            }
        }
        if (!missingPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQUEST_BACKGROUND_LOCATION);
                } else {
                    initializeApplication();
                }
            } else {
                initializeApplication();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            StringBuilder missingPerms = new StringBuilder();
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    missingPerms.append(permissions[i]).append(" ");
                }
            }
            if (allGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQUEST_BACKGROUND_LOCATION);
                    } else {
                        initializeApplication();
                    }
                } else {
                    initializeApplication();
                }
            } else {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
                updateStatus("需以下权限才能使用：" + missingPerms.toString());
            }
        } else if (requestCode == REQUEST_BACKGROUND_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeApplication();
                updateStatus("后台定位权限已授予");
            } else {
                initializeApplication();
                updateStatus("后台定位权限未授予，部分功能受限");
            }
        }
    }

    private void initializeApplication() {
        initTFLiteModel();
        initTextToSpeech();
        initVibrator();
        initVoiceNaviHelper();
        startCamera();
    }

    private void initTFLiteModel() {
        try {
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            String modelFile = findModelFile();
            if (modelFile == null) {
                Log.e(TAG, "未找到模型文件，启用模拟检测");
                updateStatus("模型缺失，启用模拟检测");
                tflite = null;
                labels = createDefaultLabels();
                return;
            }
            MappedByteBuffer modelBuffer = loadModelFile(modelFile);
            tflite = new Interpreter(modelBuffer, options);
            labels = loadLabels();
            updateStatus("模型加载成功：" + modelFile + "（类别数：" + labels.size() + "）");
            Log.d(TAG, "TFLite模型初始化完成");
            printModelInfo();
        } catch (Exception e) {
            Log.e(TAG, "模型初始化失败", e);
            updateStatus("模型加载失败，启用模拟检测");
            tflite = null;
            labels = createDefaultLabels();
        }
    }

    private void initTextToSpeech() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.CHINESE);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "设备不支持中文TTS");
                    updateStatus("TTS：不支持中文");
                } else {
                    tts.setSpeechRate(1.0f);
                    tts.setPitch(1.0f);
                    updateStatus("TTS初始化成功");
                }
            } else {
                Log.e(TAG, "TTS初始化失败");
                updateStatus("TTS初始化失败");
            }
        });
    }

    private void initVibrator() {
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            Log.w(TAG, "设备不支持震动");
            updateStatus("震动：设备不支持");
        } else {
            updateStatus("震动服务就绪");
        }
    }

    /**
     * 初始化语音导航助手，传入盲道信息提供者
     */
    private void initVoiceNaviHelper() {
        if (tts == null) {
            Log.e(TAG, "TTS未初始化，语音导航助手启动失败");
            return;
        }
        voiceNaviHelper = new VoiceNaviHelper(this, tts, new VoiceNaviHelper.NaviStatusListener() {
            @Override
            public void onLocationUpdate(String location, float accuracy) {
                currentLocation = location;
                runOnUiThread(() -> updateStatus("定位更新: " + location + " 精度:" + accuracy + "米"));
            }

            @Override
            public void onNavigationStateChange(boolean navigating) {
                isNavigating = navigating;
                runOnUiThread(() -> updateStatus("导航状态: " + (navigating ? "运行中" : "未启动")));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> updateStatus("导航错误: " + error));
            }

            @Override
            public void onStatusMessage(String message) {
                runOnUiThread(() -> updateStatus(message));
            }
        }, () -> getBlindRoadInfo());  // 传入盲道信息提供者
        Log.d(TAG, "语音导航助手初始化完成");
    }

    private void speakAndVibrate(String text) {
        runOnUiThread(() -> {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            updateStatus("服务器指令：" + text + " | 当前位置：" + currentLocation);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VIBRATE_PATTERN, -1);
            }
            if (voiceNaviHelper != null) {
                voiceNaviHelper.speak(text);
            } else if (tts != null) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "server_" + System.currentTimeMillis());
            }
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().setTargetResolution(new Size(640, 480)).build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(analysisExecutor, this::analyzeImage);
                CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                setupPreviewScale();
                updateStatus("实时检测已启动 | 当前位置：" + currentLocation);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "相机启动失败", e);
                updateStatus("相机启动失败：" + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void setupPreviewScale() {
        previewView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                previewView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                previewWidth = previewView.getWidth();
                previewHeight = previewView.getHeight();
                float scaleX = (float) previewWidth / INPUT_SIZE;
                float scaleY = (float) previewHeight / INPUT_SIZE;
                overlayView.setScale(scaleX, scaleY);
            }
        });
    }

    private void analyzeImage(@NonNull ImageProxy image) {
        try {
            long frameStartTime = SystemClock.uptimeMillis();
            Bitmap bitmap = imageToBitmap(image);
            if (bitmap == null) {
                image.close();
                return;
            }
            DetectionResultWithTime resultWithTime = tflite != null ? detectObjects(bitmap) : new DetectionResultWithTime(simulateDetection(bitmap), 0);
            List<DetectionResult> results = resultWithTime.results;
            long inferenceTime = resultWithTime.inferenceTimeMs;
            sendResultsToServer(results, bitmap.getWidth() + "x" + bitmap.getHeight(), inferenceTime);
            runOnUiThread(() -> {
                currentDetectionResults = new ArrayList<>(results);
                overlayView.setResults(results);
                overlayView.invalidate();
                updateFPS(frameStartTime);
                triggerVoiceAndVibrate(results);
                updateStatusWithDistance(results, inferenceTime);
            });
        } catch (Exception e) {
            Log.e(TAG, "图像分析失败", e);
        } finally {
            image.close();
        }
    }

    private void triggerVoiceAndVibrate(List<DetectionResult> results) {
        List<DetectionResult> dangerResults = new ArrayList<>();
        for (DetectionResult result : results) {
            if (result.getConfidence() <= 0.7f) continue;
            int centerX = (result.getLeft() + result.getRight()) / 2;
            if (centerX < INPUT_SIZE * 0.33 || centerX > INPUT_SIZE * 0.66) continue;
            if (result.getDistance() > 5.0f || result.getDistance() <= 0) continue;
            if (isSafeObject(result.getLabel())) continue;
            dangerResults.add(result);
        }
        if (dangerResults.isEmpty()) return;
        String speakText = generateSpeakTextWithDistance(dangerResults);
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSpeakTime < SPEAK_COOLDOWN) return;
        lastSpeakTime = currentTime;
        lastSpokenText = speakText;
        Log.d(TAG, "⚠️ 危险障碍物播报：" + speakText);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VIBRATE_PATTERN, -1);
        }
        if (voiceNaviHelper != null) {
            voiceNaviHelper.speak(speakText, true, false);
        } else if (tts != null) {
            tts.speak(speakText, TextToSpeech.QUEUE_FLUSH, null, "detect_" + System.currentTimeMillis());
        }
    }

    private String generateSpeakTextWithDistance(List<DetectionResult> results) {
        Map<String, List<DetectionResult>> groupMap = new HashMap<>();
        for (DetectionResult res : results) {
            String label = res.getLabel();
            if (!groupMap.containsKey(label)) groupMap.put(label, new ArrayList<>());
            groupMap.get(label).add(res);
        }
        StringBuilder sb = new StringBuilder("正前方");
        for (Map.Entry<String, List<DetectionResult>> entry : groupMap.entrySet()) {
            String label = entry.getKey();
            int count = entry.getValue().size();
            float minDist = entry.getValue().stream().min(Comparator.comparing(DetectionResult::getDistance)).get().getDistance();
            if (sb.length() > 4) sb.append("，");
            if (count > 1) {
                sb.append(String.format("有%d个%s", count, label));
            } else {
                sb.append(String.format("有%s", label));
            }
            sb.append(String.format("，距离%.1f米", minDist));
        }
        sb.append("，注意避让");
        return sb.toString();
    }

    private boolean isSafeObject(String label) {
        List<String> safeObjects = Arrays.asList("斑马线", "盲道", "绿灯", "交通标识", "垃圾桶", "消防栓", "电线杆");
        return safeObjects.contains(label);
    }

    private float calculateDistance(String label, float objectWidthPx) {
        float realWidthCm = OBJECT_REAL_WIDTH_CM.getOrDefault(label, 50f);
        if (objectWidthPx <= 0 || FOCAL_LENGTH_PX <= 0) return -1f;
        float distanceCm = (realWidthCm * FOCAL_LENGTH_PX) / objectWidthPx;
        return distanceCm / 100;
    }

    private DetectionResultWithTime detectObjects(Bitmap bitmap) {
        long inferenceStartTime = SystemClock.uptimeMillis();
        List<DetectionResult> results = new ArrayList<>();
        try {
            Bitmap processedBitmap = cropBitmapToSquare(bitmap);
            processedBitmap = Bitmap.createScaledBitmap(processedBitmap, INPUT_SIZE, INPUT_SIZE, true);
            ByteBuffer inputBuffer = convertBitmapToByteBuffer(processedBitmap);
            results = runInferenceAndParse(inputBuffer, bitmap);
        } catch (Exception e) {
            Log.e(TAG, "模型推理失败", e);
        }
        long inferenceTime = SystemClock.uptimeMillis() - inferenceStartTime;
        return new DetectionResultWithTime(results, inferenceTime);
    }

    private Bitmap cropBitmapToSquare(Bitmap source) {
        int size = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - size) / 2;
        int y = (source.getHeight() - size) / 2;
        return Bitmap.createBitmap(source, x, y, size, size);
    }

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());
        inputBuffer.rewind();
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        for (int pixel : pixels) {
            float r = ((pixel >> 16) & 0xFF) / 255.0f;
            float g = ((pixel >> 8) & 0xFF) / 255.0f;
            float b = (pixel & 0xFF) / 255.0f;
            inputBuffer.putFloat(r);
            inputBuffer.putFloat(g);
            inputBuffer.putFloat(b);
        }
        return inputBuffer;
    }

    private List<DetectionResult> runInferenceAndParse(ByteBuffer inputBuffer, Bitmap originalBitmap) {
        try {
            if (tflite.getOutputTensorCount() == 0) return new ArrayList<>();
            int[] outputShape = tflite.getOutputTensor(0).shape();
            float[][][] output = new float[1][outputShape[1]][outputShape[2]];
            tflite.run(inputBuffer, output);
            boolean needTranspose = (outputShape[1] == 84 || outputShape[1] == 24) && outputShape[2] == 8400;
            float[][] detectionOutput;
            if (needTranspose) {
                detectionOutput = transposeOutput(output[0]);
            } else {
                detectionOutput = output[0];
            }
            return parseDetections(detectionOutput, originalBitmap.getWidth(), originalBitmap.getHeight());
        } catch (Exception e) {
            Log.e(TAG, "推理结果解析失败", e);
            return new ArrayList<>();
        }
    }

    private float[][] transposeOutput(float[][] output) {
        int rows = output.length;
        int cols = output[0].length;
        float[][] transposed = new float[cols][rows];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                transposed[j][i] = output[i][j];
            }
        }
        return transposed;
    }

    private List<DetectionResult> parseDetections(float[][] detections, int imgWidth, int imgHeight) {
        List<DetectionResult> results = new ArrayList<>();
        for (float[] detection : detections) {
            int numClasses = detection.length - 4;
            if (numClasses <= 0) continue;
            float cx = detection[0] * INPUT_SIZE;
            float cy = detection[1] * INPUT_SIZE;
            float w = detection[2] * INPUT_SIZE;
            float h = detection[3] * INPUT_SIZE;
            cx = Math.max(0, Math.min(cx, INPUT_SIZE));
            cy = Math.max(0, Math.min(cy, INPUT_SIZE));
            w = Math.max(0, Math.min(w, INPUT_SIZE));
            h = Math.max(0, Math.min(h, INPUT_SIZE));
            int x1 = (int) (cx - w / 2);
            int y1 = (int) (cy - h / 2);
            int x2 = (int) (cx + w / 2);
            int y2 = (int) (cy + h / 2);
            x1 = Math.max(0, Math.min(x1, INPUT_SIZE));
            y1 = Math.max(0, Math.min(y1, INPUT_SIZE));
            x2 = Math.max(0, Math.min(x2, INPUT_SIZE));
            y2 = Math.max(0, Math.min(y2, INPUT_SIZE));
            if (x2 <= x1 || y2 <= y1) continue;
            int maxClassId = -1;
            float maxConfidence = 0f;
            for (int i = 0; i < numClasses; i++) {
                float conf = detection[4 + i];
                if (conf > maxConfidence) {
                    maxConfidence = conf;
                    maxClassId = i;
                }
            }
            if (maxConfidence > CONFIDENCE_THRESHOLD && maxClassId >= 0 && maxClassId < labels.size()) {
                String label = labels.get(maxClassId);
                DetectionResult result = new DetectionResult(label, maxConfidence, x1, y1, x2, y2);
                result.setDistance(calculateDistance(label, w));
                results.add(result);
            }
        }
        return applyNMS(results);
    }

    private List<DetectionResult> applyNMS(List<DetectionResult> detections) {
        if (detections.isEmpty()) return detections;
        detections.sort((a, b) -> Float.compare(b.getConfidence(), a.getConfidence()));
        List<DetectionResult> filtered = new ArrayList<>();
        boolean[] suppressed = new boolean[detections.size()];
        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            DetectionResult current = detections.get(i);
            filtered.add(current);
            for (int j = i + 1; j < detections.size(); j++) {
                if (suppressed[j]) continue;
                if (calculateIOU(current, detections.get(j)) > IOU_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }
        return filtered;
    }

    private float calculateIOU(DetectionResult box1, DetectionResult box2) {
        int intersectLeft = Math.max(box1.getLeft(), box2.getLeft());
        int intersectTop = Math.max(box1.getTop(), box2.getTop());
        int intersectRight = Math.min(box1.getRight(), box2.getRight());
        int intersectBottom = Math.min(box1.getBottom(), box2.getBottom());
        int intersectArea = Math.max(0, intersectRight - intersectLeft) * Math.max(0, intersectBottom - intersectTop);
        if (intersectArea == 0) return 0f;
        int area1 = box1.getWidth() * box1.getHeight();
        int area2 = box2.getWidth() * box2.getHeight();
        return (float) intersectArea / (area1 + area2 - intersectArea);
    }

    private List<DetectionResult> simulateDetection(Bitmap bitmap) {
        List<DetectionResult> results = new ArrayList<>();
        Random random = new Random();
        int numBoxes = random.nextInt(3) + 1;
        for (int i = 0; i < numBoxes; i++) {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int boxW = w / 5 + random.nextInt(w / 5);
            int boxH = h / 5 + random.nextInt(h / 5);
            int left = random.nextInt(w - boxW);
            int top = random.nextInt(h - boxH);
            String label = labels.get(random.nextInt(Math.min(10, labels.size())));
            float confidence = 0.7f + random.nextFloat() * 0.2f;
            DetectionResult result = new DetectionResult(label, confidence, left, top, left + boxW, top + boxH);
            result.setDistance(2 + random.nextFloat() * 8);
            results.add(result);
        }
        return results;
    }

    private Bitmap imageToBitmap(ImageProxy image) {
        try {
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            if (planes == null || planes.length < 3) return null;
            ByteBuffer yBuf = planes[0].getBuffer();
            ByteBuffer uBuf = planes[1].getBuffer();
            ByteBuffer vBuf = planes[2].getBuffer();
            int yRowStride = planes[0].getRowStride();
            int uRowStride = planes[1].getRowStride();
            int vRowStride = planes[2].getRowStride();
            int width = image.getWidth();
            int height = image.getHeight();
            byte[] nv21 = new byte[width * height * 3 / 2];
            int yIdx = 0, uvIdx = width * height;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    nv21[yIdx++] = yBuf.get(y * yRowStride + x);
                }
            }
            for (int y = 0; y < height / 2; y++) {
                for (int x = 0; x < width / 2; x++) {
                    nv21[uvIdx++] = vBuf.get(y * vRowStride + x);
                    nv21[uvIdx++] = uBuf.get(y * uRowStride + x);
                }
            }
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 90, os);
            byte[] jpegData = os.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "ImageProxy转Bitmap失败", e);
            return null;
        }
    }

    private void sendResultsToServer(List<DetectionResult> results, String imageSize, long inferenceTime) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSendTime < SEND_INTERVAL || results.isEmpty()) return;
        lastSendTime = currentTime;
        networkUtils.sendDetectionResults(results, imageSize, new NetworkUtils.DetectionCallback() {
            @Override
            public void onSuccess(String response) {
                handleServerResponse(response);
            }

            @Override
            public void onFailure(String error) {
                runOnUiThread(() -> updateStatus("发送失败: " + error));
            }
        });
    }

    private void handleServerResponse(String response) {
        try {
            JSONObject json = new JSONObject(response);
            if (json.has("voice_command")) {
                String cmd = json.getString("voice_command");
                speakAndVibrate(cmd);
            }
            if (json.has("alert") && json.getBoolean("alert")) {
                triggerAlert();
            }
            if (json.has("navi_destination")) {
                String destination = json.getString("navi_destination");
                if (voiceNaviHelper != null) {
                    voiceNaviHelper.startNavigation(destination);
                }
            }
            if (json.has("status")) {
                runOnUiThread(() -> {
                    try {
                        updateStatus("服务器: " + json.getString("status") + " | 当前位置：" + currentLocation);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                });
            }
        } catch (JSONException e) {
            Log.e(TAG, "解析服务器响应失败", e);
        }
    }

    private void triggerAlert() {
        runOnUiThread(() -> {
            overlayView.setAlertMode(true);
            Toast.makeText(this, "⚠️ 检测到重要目标！当前位置：" + currentLocation, Toast.LENGTH_LONG).show();
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] alertPattern = {0, 200, 100, 200};
                vibrator.vibrate(alertPattern, -1);
            }
        });
        new Thread(() -> {
            SystemClock.sleep(2000);
            runOnUiThread(() -> overlayView.setAlertMode(false));
        }).start();
    }

    private void updateFPS(long frameStartTime) {
        frameCount++;
        long elapsed = frameStartTime - lastAnalysisTime;
        if (elapsed >= 1000) {
            currentFPS = frameCount * 1000f / elapsed;
            fpsText.setText(String.format("FPS: %.1f", currentFPS));
            frameCount = 0;
            lastAnalysisTime = frameStartTime;
        }
    }

    private void updateStatusWithDistance(List<DetectionResult> results, long inferenceTime) {
        StringBuilder status = new StringBuilder();
        status.append(String.format("检测到%d个目标 | 推理耗时%dms | FPS:%.1f", results.size(), inferenceTime, currentFPS));
        if (!results.isEmpty()) {
            status.append(" | 距离：");
            for (int i = 0; i < results.size(); i++) {
                DetectionResult result = results.get(i);
                if (i > 0) status.append("、");
                status.append(result.getLabel()).append("(").append(result.getDistance() > 0 ? String.format("%.1fm", result.getDistance()) : "未知").append(")");
            }
        }
        status.append(" | 位置：").append(currentLocation);
        status.append(" | 导航：").append(isNavigating ? "运行中" : "未启动");
        updateStatus(status.toString());
    }

    private void updateStatus(String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    private String findModelFile() {
        String[] possibleModels = {"best_float16.tflite", "best_float32.tflite"};
        for (String model : possibleModels) {
            if (checkFileExists(model)) return model;
        }
        return null;
    }

    private List<String> loadLabels() {
        String[] possibleLabelFiles = {"labels.txt"};
        for (String labelFile : possibleLabelFiles) {
            if (checkFileExists(labelFile)) {
                try {
                    return FileUtil.loadLabels(this, labelFile);
                } catch (IOException e) {
                    Log.w(TAG, "标签文件加载失败：" + labelFile, e);
                }
            }
        }
        Log.w(TAG, "使用默认COCO标签");
        return createDefaultLabels();
    }

    private void printModelInfo() {
        if (tflite == null) return;
        try {
            int inputCount = tflite.getInputTensorCount();
            int outputCount = tflite.getOutputTensorCount();
            Log.d(TAG, "模型输入张量数：" + inputCount + "，输出张量数：" + outputCount);
            for (int i = 0; i < inputCount; i++) {
                Log.d(TAG, "输入" + i + "形状：" + Arrays.toString(tflite.getInputTensor(i).shape()));
            }
            for (int i = 0; i < outputCount; i++) {
                Log.d(TAG, "输出" + i + "形状：" + Arrays.toString(tflite.getOutputTensor(i).shape()));
            }
        } catch (Exception e) {
            Log.e(TAG, "获取模型信息失败", e);
        }
    }

    private boolean checkFileExists(String filename) {
        try {
            InputStream is = getAssets().open(filename);
            is.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private List<String> createDefaultLabels() {
        return Arrays.asList("行人", "自行车", "汽车", "摩托车", "飞机", "公交车", "火车", "卡车", "船", "交通信号灯", "消防栓", "停止标志", "停车计时器", "长凳", "鸟", "猫", "狗", "马", "羊", "牛");
    }

    private MappedByteBuffer loadModelFile(String modelPath) throws IOException {
        return FileUtil.loadMappedFile(this, modelPath);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (vibrator != null) vibrator.cancel();
        if (voiceNaviHelper != null) {
            voiceNaviHelper.destroy();
            voiceNaviHelper = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (analysisExecutor != null) analysisExecutor.shutdown();
        if (tflite != null) tflite.close();
        if (networkUtils != null) {
            networkUtils.shutdown();
        }
    }

    private static class DetectionResultWithTime {
        List<DetectionResult> results;
        long inferenceTimeMs;
        DetectionResultWithTime(List<DetectionResult> results, long inferenceTimeMs) {
            this.results = results;
            this.inferenceTimeMs = inferenceTimeMs;
        }
    }

    public String getBlindRoadInfo() {
        if (currentDetectionResults == null || currentDetectionResults.isEmpty()) {
            return "当前没有检测到盲道";
        }
        List<DetectionResult> blindRoads = new ArrayList<>();
        for (DetectionResult res : currentDetectionResults) {
            if ("盲道".equals(res.getLabel())) {
                blindRoads.add(res);
            }
        }
        if (blindRoads.isEmpty()) {
            return "当前没有检测到盲道";
        }
        DetectionResult nearest = Collections.min(blindRoads, Comparator.comparing(DetectionResult::getDistance));
        float distance = nearest.getDistance();
        int centerX = (nearest.getLeft() + nearest.getRight()) / 2;
        float screenCenter = INPUT_SIZE / 2f;
        String direction;
        if (centerX < screenCenter * 0.8f) {
            direction = "左前方";
        } else if (centerX > screenCenter * 1.2f) {
            direction = "右前方";
        } else {
            direction = "正前方";
        }
        return String.format("盲道在您%s，距离约%.1f米", direction, distance);
    }
}
