package com.example.cameraupload;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import android.annotation.SuppressLint;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.AMapNaviListener;
import com.amap.api.navi.model.*;
import com.amap.api.services.core.AMapException;
import com.amap.api.services.core.PoiItem;
import com.amap.api.services.poisearch.PoiResult;
import com.amap.api.services.poisearch.PoiSearch;

import com.iflytek.sparkchain.core.asr.ASR;
import com.iflytek.sparkchain.core.asr.AsrCallbacks;
import com.iflytek.sparkchain.core.SparkChain;
import com.iflytek.sparkchain.core.SparkChainConfig;
import com.iflytek.aikit.core.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VoiceNaviHelper implements AMapLocationListener {
    private static final String TAG = "VoiceNaviHelper";

    private final android.app.Activity activity;
    private final Context appContext;
    private TextToSpeech tts;
    private final NaviStatusListener naviListener;
    private final Vibrator vibrator;

    public interface BlindRoadProvider {
        String getBlindRoadInfo();
    }
    private final BlindRoadProvider blindRoadProvider;

    // 高德导航
    private AMapLocationClient mLocationClient;
    private AMapNavi mAMapNavi;
    private String currentLocation = "未知位置";
    private boolean isNavigating = false;

    // POI搜索相关
    private PoiSearch poiSearch;
    private String pendingDestination;
    private String currentCity = "";

    // SparkChain 在线听写
    private ASR mAsr;
    private boolean isListening = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean sparkChainInitialized = false;

    // 唤醒词与指令
    private static final String WAKE_WORD = "小瞳小瞳";
    private static final String CMD_NAVI_PREFIX = "导航到";
    private static final String CMD_STOP = "停止导航";
    private static final String CMD_WHERE = "我在哪";
    private static final String CMD_HELP = "我需要帮助";
    private static final Pattern NAVI_PATTERN = Pattern.compile(CMD_NAVI_PREFIX + "(.*)");
    private static final List<String> VALID_KEYWORDS = new ArrayList<>();
    static {
        VALID_KEYWORDS.add(CMD_STOP);
        VALID_KEYWORDS.add(CMD_WHERE);
        VALID_KEYWORDS.add(CMD_HELP);
        VALID_KEYWORDS.add(CMD_NAVI_PREFIX);
        VALID_KEYWORDS.add("去");
        VALID_KEYWORDS.add("到");
        VALID_KEYWORDS.add("路线");
        VALID_KEYWORDS.add("盲道");
    }

    // 录音相关常量
    private static final int SAMPLE_RATE = 16000;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int BUFFER_SIZE = 1280;

    // 在线听写录音
    private AudioRecord asrAudioRecord;
    private boolean isAsrRecording = false;
    private Thread asrRecordingThread;
    private AtomicBoolean shouldWrite = new AtomicBoolean(true);

    // 唤醒录音
    private AudioRecord wakeupAudioRecord;
    private boolean isWakeupRecording = false;
    private Thread wakeupRecordingThread;
    private AtomicBoolean firstFrame = new AtomicBoolean(true);

    // 定位守护
    private long lastLocationTime = 0;
    private static final long LOCATION_TIMEOUT = 5000;
    private int locationRetryCount = 0;
    private static final int MAX_LOCATION_RETRY = 3;

    // AIKit 唤醒相关
    private static final String ABILITY_IVW = "e867a88f2";
    private AiHandle wakeupAiHandle;
    private boolean isWakeupActive = false;
    private boolean isWakeupWordLoaded = false;
    private static boolean aiKitInitialized = false;

    // 播报队列
    private LinkedList<String> ttsQueue = new LinkedList<>();
    private boolean isSpeaking = false;
    private boolean pendingStartListen = false;
    private final AtomicBoolean isTtsSpeaking = new AtomicBoolean(false);
    private final AtomicBoolean isStartingWakeup = new AtomicBoolean(false);

    // 自语音过滤
    private String lastTtsText = "";
    private int repeatTtsCount = 0;
    private static final int TTS_REPEAT_MAX = 2;
    private Handler ttsFilterHandler = new Handler(Looper.getMainLooper());

    // 无效指令冷却
    private long lastInvalidCommandTime = 0;
    private static final long INVALID_COMMAND_COOLDOWN = 3000;

    // 听写超时
    private Handler listenTimeoutHandler = new Handler(Looper.getMainLooper());
    private static final long LISTEN_TIMEOUT = 15000;

    // 唤醒健康检查
    private Runnable wakeupHealthCheck;
    private static final long WAKEUP_HEALTH_INTERVAL = 8000;

    // 唤醒启动重试
    private int wakeupRetryCount = 0;
    private static final int MAX_WAKEUP_RETRY = 3;

    // 音频焦点管理
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = focusChange -> {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                stopListenIfNeeded();
                stopWakeup();
                if (tts != null) tts.stop();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                stopListenIfNeeded();
                stopWakeup();
                break;
        }
    };

    //求助留言录音相关
    private AudioRecord helpAudioRecord;
    private Thread helpRecordingThread;
    private boolean isHelpRecording = false;
    private File helpAudioFile;                   // 最终 WAV 文件
    private ASR helpAsr;                          // 留言专用 ASR
    private StringBuilder helpTranscriptBuilder = new StringBuilder();
    private boolean helpAsrFinished = false;
    private Handler helpRecordHandler = new Handler(Looper.getMainLooper());
    private static final long HELP_RECORD_MAX_DURATION = 10000; // 最长10秒

    //接口定义
    public interface NaviStatusListener {
        void onLocationUpdate(String location, float accuracy);
        void onNavigationStateChange(boolean isNavigating);
        void onError(String error);
        void onStatusMessage(String message);
    }

    // 构造函数
    public VoiceNaviHelper(android.app.Activity activity, TextToSpeech tts,
                           NaviStatusListener listener, BlindRoadProvider provider) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
        this.tts = tts;
        this.naviListener = listener;
        this.vibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
        this.blindRoadProvider = provider;

        AMapLocationClient.setApiKey(activity.getString(R.string.amap_key));

        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .setWillPauseWhenDucked(true)
                    .build();
        }

        initSparkChain();
        initAMapNavi();
        initASR();
        initAIKit();
        startLocationGuard();
        setupTTSListener();
        setupWakeupHealthCheck();
    }

    private void setupWakeupHealthCheck() {
        wakeupHealthCheck = () -> {
            if (isWakeupActive && !isListening && !isSpeaking) {
                Log.w(TAG, "唤醒监听无响应，执行重置");
                fullReset();
            }
            mainHandler.postDelayed(wakeupHealthCheck, WAKEUP_HEALTH_INTERVAL);
        };
        mainHandler.postDelayed(wakeupHealthCheck, WAKEUP_HEALTH_INTERVAL);
    }

    private void fullReset() {
        Log.d(TAG, "========== 执行完全重置 ==========");
        stopListenIfNeeded();
        stopWakeup();
        if (mAsr != null) {
            mAsr.stop(true);
            mAsr = null;
        }
        AiHelper.getInst().unInit();
        aiKitInitialized = false;
        isWakeupWordLoaded = false;
        initAIKit();
        initASR();
    }

    private void resetWakeupWords() {
        Log.d(TAG, "========== 重置唤醒词 ==========");
        stopWakeup();
        if (isWakeupWordLoaded) {
            int ret = AiHelper.getInst().unLoadData(ABILITY_IVW, "key_word", 0);
            if (ret == 0) {
                Log.d(TAG, "唤醒词资源卸载成功");
                isWakeupWordLoaded = false;
            } else {
                Log.e(TAG, "唤醒词资源卸载失败, ret=" + ret);
                fullReset();
                return;
            }
        }
        loadCustomWakeupWords();
        startWakeup();
    }

    private void resetASROnly() {
        Log.d(TAG, "重置 ASR（不重置唤醒词）");
        if (mAsr != null) {
            mAsr.stop(true);
            mAsr = null;
        }
        initASR();
        stopWakeup();
        mainHandler.postDelayed(this::startWakeup, 300);
    }

    private void initAIKit() {
        if (aiKitInitialized) {
            Log.d(TAG, "AIKit已初始化，跳过");
            return;
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ 无录音权限，无法启动唤醒");
            if (naviListener != null) naviListener.onError("缺少录音权限，请在设置中开启");
            return;
        }
        String workDir = appContext.getExternalFilesDir("aikit").getAbsolutePath();
        String logPath = workDir + "/aeeLog.txt";
        Log.d(TAG, "AIKit 工作目录: " + workDir);
        AiHelper.getInst().setLogInfo(LogLvl.VERBOSE, 2, logPath);
        AiHelper.getInst().setLogMode(2);
        boolean needPrepare = !new File(workDir, "IVW_FILLER_1").exists();
        if (needPrepare) {
            prepareWakeupResources(workDir);
        } else {
            Log.d(TAG, "资源已存在，跳过复制");
        }
        AiHelper.getInst().registerListener(new AuthListener() {
            @Override
            public void onAuthStateChange(ErrType type, int code) {
                Log.d(TAG, "📢 授权状态变化，type=" + type + ", code=" + code);
                if (code == 0) {
                    Log.d(TAG, "✅ 授权验证成功，准备加载唤醒词并启动");
                    aiKitInitialized = true;
                    loadCustomWakeupWords();
                    mainHandler.postDelayed(() -> startWakeup(), 1000);
                } else {
                    Log.e(TAG, "❌ 授权失败，错误码：" + code);
                    if (naviListener != null) naviListener.onError("授权失败，错误码：" + code);
                }
            }
        });
        AiHelper.getInst().registerListener(ABILITY_IVW, new AiListener() {
            @Override
            public void onResult(int handleID, List<AiResponse> outputData, Object usrContext) {
                if (outputData == null || outputData.isEmpty()) return;
                for (AiResponse resp : outputData) {
                    byte[] data = resp.getValue();
                    if (data == null) continue;
                    String valueStr = new String(data, StandardCharsets.UTF_8);
                    Log.d(TAG, "唤醒结果 key=" + resp.getKey() + ", value=" + valueStr);
                    if ("func_wake_up".equals(resp.getKey()) || "func_pre_wakeup".equals(resp.getKey())) {
                        try {
                            if (valueStr.contains("\"keyword\":\"" + WAKE_WORD + "\"")) {
                                Log.d(TAG, "✅ 识别到目标唤醒词");
                                handleWakeupSuccess();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "解析唤醒结果异常", e);
                        }
                    }
                }
            }

            @Override
            public void onEvent(int handleID, int event, List<AiResponse> eventData, Object usrContext) {
                Log.d(TAG, "唤醒事件, event=" + event);
                if (event == 2) {
                    isWakeupActive = false;
                    if (!isListening && !isSpeaking) {
                        mainHandler.postDelayed(() -> startWakeup(), 500);
                    }
                }
            }

            @Override
            public void onError(int handleID, int err, String msg, Object usrContext) {
                Log.e(TAG, "唤醒错误, err=" + err + ", msg=" + msg);
                isWakeupActive = false;
                mainHandler.postDelayed(() -> {
                    if (!isListening && !isSpeaking) startWakeup();
                }, 3000);
            }
        });
        AiHelper.Params params = AiHelper.Params.builder()
                .appId(activity.getString(R.string.appid))
                .apiKey(activity.getString(R.string.apikey))
                .apiSecret(activity.getString(R.string.apiSecret))
                .ability(ABILITY_IVW)
                .workDir(workDir)
                .resDir(workDir)
                .build();
        AiHelper.getInst().init(appContext, params);
        Log.d(TAG, "AIKit 初始化调用完成，等待授权回调...");
    }

    private void loadCustomWakeupWords() {
        if (isWakeupWordLoaded) return;
        String workDir = appContext.getExternalFilesDir("aikit").getAbsolutePath();
        String keywordPath = workDir + "/keyword1.txt";
        File kwFile = new File(keywordPath);
        try {
            if (!kwFile.exists()) kwFile.createNewFile();
            boolean needRewrite = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(kwFile), "UTF-8"))) {
                String line = reader.readLine();
                if (line == null || !line.trim().endsWith(";")) needRewrite = true;
            } catch (Exception e) { needRewrite = true; }
            if (needRewrite) {
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(kwFile), "UTF-8"))) {
                    writer.write(WAKE_WORD + ";");
                    writer.newLine();
                }
                Log.d(TAG, "已重新生成标准唤醒词文件: " + WAKE_WORD + ";");
            }
        } catch (IOException e) {
            Log.e(TAG, "唤醒词文件操作失败", e);
            return;
        }
        AiRequest.Builder customBuilder = AiRequest.builder();
        customBuilder.customText("key_word", keywordPath, 0);
        int ret = AiHelper.getInst().loadData(ABILITY_IVW, customBuilder.build());
        if (ret != 0) {
            Log.e(TAG, "loadData 失败，错误码：" + ret);
            return;
        }
        int[] indexs = {0};
        ret = AiHelper.getInst().specifyDataSet(ABILITY_IVW, "key_word", indexs);
        if (ret == 0) {
            isWakeupWordLoaded = true;
            Log.d(TAG, "✅ 自定义唤醒词加载成功");
        } else {
            Log.e(TAG, "specifyDataSet 失败，错误码：" + ret);
        }
    }

    private void prepareWakeupResources(String workDir) {
        File workDirFile = new File(workDir);
        if (workDirFile.exists()) {
            File[] files = workDirFile.listFiles();
            if (files != null) for (File file : files) if (file.isFile()) file.delete();
        } else workDirFile.mkdirs();
        copyAssetsToDirectory("aikit_resources/ivw", workDir);
        File[] resFiles = workDirFile.listFiles();
        if (resFiles != null) {
            for (File f : resFiles) Log.d(TAG, f.getName() + " 大小: " + f.length() + " bytes");
        }
        Log.d(TAG, "资源复制完成，工作目录: " + workDir);
    }

    private void copyAssetsToDirectory(String assetPath, String destDir) {
        try {
            String[] files = activity.getAssets().list(assetPath);
            if (files == null) return;
            for (String fileName : files) {
                String fullAssetPath = assetPath + "/" + fileName;
                File outFile = new File(destDir, fileName);
                try (InputStream is = activity.getAssets().open(fullAssetPath);
                     OutputStream os = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = is.read(buffer)) != -1) os.write(buffer, 0, len);
                    Log.d(TAG, "复制资源: " + fileName);
                } catch (IOException e) {
                    Log.w(TAG, "跳过不存在的资源: " + fileName);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "列出 assets 资源失败", e);
        }
    }

    private void stopWakeup() {
        if (!isWakeupActive && wakeupAiHandle == null && !isWakeupRecording) return;
        Log.d(TAG, "停止唤醒监听");
        isWakeupActive = false;
        isWakeupRecording = false;
        if (wakeupRecordingThread != null) {
            try {
                wakeupRecordingThread.join(1500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            wakeupRecordingThread = null;
        }
        if (wakeupAudioRecord != null) {
            try {
                wakeupAudioRecord.stop();
                wakeupAudioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "释放唤醒录音异常", e);
            }
            wakeupAudioRecord = null;
        }
        if (wakeupAiHandle != null) {
            AiHelper.getInst().end(wakeupAiHandle);
            wakeupAiHandle = null;
        }
        Log.d(TAG, "⏹️ 唤醒监听已停止");
    }

    private void startWakeup() {
        if (isStartingWakeup.compareAndSet(false, true)) {
            try {
                if (isSpeaking) {
                    mainHandler.postDelayed(() -> {
                        isStartingWakeup.set(false);
                        startWakeup();
                    }, 500);
                    return;
                }
                if (isListening) {
                    Log.d(TAG, "正在听写，跳过启动唤醒");
                    isStartingWakeup.set(false);
                    return;
                }
                if (!aiKitInitialized) {
                    Log.d(TAG, "AIKit未初始化，稍后重试");
                    mainHandler.postDelayed(() -> {
                        isStartingWakeup.set(false);
                        startWakeup();
                    }, 1000);
                    return;
                }
                if (wakeupAiHandle != null) {
                    AiHelper.getInst().end(wakeupAiHandle);
                    wakeupAiHandle = null;
                }
                if (isWakeupActive) {
                    isWakeupActive = false;
                }
                AiRequest.Builder paramBuilder = AiRequest.builder();
                paramBuilder.param("wdec_param_nCmThreshold", "0 0:800");
                paramBuilder.param("gramLoad", true);
                AiHandle handle = AiHelper.getInst().start(ABILITY_IVW, paramBuilder.build(), null);
                if (!handle.isSuccess()) {
                    int code = handle.getCode();
                    Log.e(TAG, "启动唤醒会话失败, code=" + code);
                    if (code == 10005) {
                        Log.e(TAG, "检测到能力错误，执行完全重置");
                        fullReset();
                        isStartingWakeup.set(false);
                        return;
                    }
                    if (wakeupRetryCount < MAX_WAKEUP_RETRY) {
                        wakeupRetryCount++;
                        mainHandler.postDelayed(() -> {
                            isStartingWakeup.set(false);
                            startWakeup();
                        }, 2000);
                    } else {
                        Log.e(TAG, "唤醒启动多次失败，执行完全重置");
                        wakeupRetryCount = 0;
                        fullReset();
                    }
                    isStartingWakeup.set(false);
                    return;
                }
                wakeupRetryCount = 0;
                this.wakeupAiHandle = handle;
                isWakeupActive = true;
                firstFrame.set(true);
                startWakeupRecording();
                Log.d(TAG, "▶️ 唤醒监听已启动");
            } finally {
                isStartingWakeup.set(false);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startWakeupRecording() {
        if (isWakeupRecording) return;
        isWakeupRecording = true;
        wakeupRecordingThread = new Thread(() -> {
            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            wakeupAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    CHANNEL_CONFIG, AUDIO_FORMAT, minBufferSize * 2);
            if (wakeupAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "唤醒 AudioRecord 初始化失败");
                isWakeupRecording = false;
                return;
            }
            try {
                wakeupAudioRecord.startRecording();
            } catch (SecurityException e) {
                Log.e(TAG, "唤醒录音权限被拒绝", e);
                isWakeupRecording = false;
                wakeupAudioRecord.release();
                wakeupAudioRecord = null;
                return;
            }
            byte[] buffer = new byte[BUFFER_SIZE];
            while (isWakeupRecording && isWakeupActive && wakeupAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                int read = wakeupAudioRecord.read(buffer, 0, buffer.length);
                if (read > 0 && wakeupAiHandle != null) {
                    short[] samples = new short[read / 2];
                    ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
                    long sum = 0;
                    for (short s : samples) sum += Math.abs(s);
                    long avgVol = sum / samples.length;
                    if (avgVol > 10) Log.v(TAG, "唤醒录音音量: " + avgVol);
                    AiStatus status = firstFrame.getAndSet(false) ? AiStatus.BEGIN : AiStatus.CONTINUE;
                    AiAudio aiAudio = AiAudio.get("wav")
                            .encoding(AiAudio.ENCODING_PCM)
                            .data(buffer.clone())
                            .status(status)
                            .valid();
                    AiRequest.Builder dataBuilder = AiRequest.builder();
                    dataBuilder.payload(aiAudio);
                    int ret = AiHelper.getInst().write(dataBuilder.build(), wakeupAiHandle, null);
                    if (ret != 0) {
                        Log.e(TAG, "唤醒数据写入失败, ret=" + ret);
                        mainHandler.post(this::resetWakeupWords);
                        break;
                    }
                }
            }
            if (wakeupAiHandle != null && !isWakeupActive) {
                AiAudio endAudio = AiAudio.get("wav")
                        .status(AiStatus.END)
                        .valid();
                AiRequest.Builder endBuilder = AiRequest.builder();
                endBuilder.payload(endAudio);
                AiHelper.getInst().write(endBuilder.build(), wakeupAiHandle, null);
            }
            if (wakeupAudioRecord != null) {
                try { wakeupAudioRecord.stop(); } catch (Exception e) { Log.e(TAG, "停止唤醒录音异常", e); }
                wakeupAudioRecord.release();
                wakeupAudioRecord = null;
            }
            isWakeupRecording = false;
        });
        wakeupRecordingThread.start();
    }

    private void handleWakeupSuccess() {
        Log.d(TAG, "✅ 唤醒成功！");
        stopWakeup();
        startListen();
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(100);
        }
        speak("我在，请说导航指令", true, false);
    }

    private void initSparkChain() {
        if (!isNetworkAvailable()) {
            Log.e(TAG, "❌ 网络不可用，无法初始化SparkChain");
            sparkChainInitialized = false;
            return;
        }
        File workDir = new File(appContext.getExternalCacheDir().getAbsolutePath());
        if (!workDir.exists()) workDir.mkdirs();
        String appId = activity.getString(R.string.appid);
        String apiKey = activity.getString(R.string.apikey);
        String apiSecret = activity.getString(R.string.apiSecret);
        SparkChainConfig config = SparkChainConfig.builder()
                .appID(appId)
                .apiKey(apiKey)
                .apiSecret(apiSecret)
                .workDir(workDir.getAbsolutePath());
        int ret = SparkChain.getInst().init(appContext, config);
        if (ret != 0) {
            Log.e(TAG, "SparkChain初始化失败，错误码：" + ret);
            sparkChainInitialized = false;
        } else {
            Log.d(TAG, "✅ SparkChain初始化成功");
            sparkChainInitialized = true;
        }
    }

    private void initAMapNavi() {
        try {
            Log.d(TAG, "高德导航SDK初始化开始");
            mLocationClient = new AMapLocationClient(appContext);
            AMapLocationClientOption option = new AMapLocationClientOption();
            option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            option.setInterval(1000);
            option.setNeedAddress(true);
            option.setSensorEnable(true);
            option.setWifiScan(true);
            option.setLocationCacheEnable(false);
            option.setMockEnable(false);
            mLocationClient.setLocationOption(option);
            mLocationClient.setLocationListener(this);
            mLocationClient.startLocation();

            mAMapNavi = AMapNavi.getInstance(appContext);
            mAMapNavi.setIsNaviTravelView(true);
            mAMapNavi.setUseInnerVoice(false);

            poiSearch = new PoiSearch(appContext, null);
            poiSearch.setOnPoiSearchListener(new PoiSearch.OnPoiSearchListener() {
                @Override
                public void onPoiSearched(PoiResult poiResult, int rCode) {
                    Log.d(TAG, "========== POI搜索回调 ==========");
                    Log.d(TAG, "rCode: " + rCode);
                    if (rCode == AMapException.CODE_AMAP_SUCCESS && poiResult != null && poiResult.getPois() != null && !poiResult.getPois().isEmpty()) {
                        PoiItem poiItem = poiResult.getPois().get(0);
                        double lat = poiItem.getLatLonPoint().getLatitude();
                        double lng = poiItem.getLatLonPoint().getLongitude();
                        String name = poiItem.getTitle();
                        Log.d(TAG, "POI搜索成功: " + name + " -> (" + lat + "," + lng + ")");
                        NaviLatLng endPoint = new NaviLatLng(lat, lng);
                        doCalculateRoute(endPoint);
                    } else {
                        Log.e(TAG, "POI搜索失败，错误码：" + rCode);
                        speak("未找到目的地", true);
                        if (naviListener != null) naviListener.onError("目的地无效");
                        pendingDestination = null;
                    }
                    startWakeup();
                }

                @Override
                public void onPoiItemSearched(PoiItem poiItem, int rCode) {}
            });

            mAMapNavi.addAMapNaviListener(new AMapNaviListener() {
                @Override public void onInitNaviFailure() { Log.e(TAG, "导航初始化失败"); }
                @Override public void onInitNaviSuccess() { Log.d(TAG, "导航初始化成功"); }
                @Override public void onStartNavi(int type) { Log.d(TAG, "步行导航开始"); }
                @Override public void onTrafficStatusUpdate() {}
                @Override public void onLocationChange(AMapNaviLocation location) {
                    if (location != null && isNavigating) {
                        NaviLatLng coord = location.getCoord();
                        if (coord != null) {
                            Log.d(TAG, "导航实时位置：纬度=" + coord.getLatitude() + "，经度=" + coord.getLongitude());
                        }
                    }
                }
                @Override public void onGetNavigationText(int type, String text) {
                    Log.d(TAG, "步行导航引导文本: " + text);
                    speak(text, true);
                }
                @Override public void onGetNavigationText(String text) { onGetNavigationText(0, text); }
                @Override public void onEndEmulatorNavi() {}
                @Override public void onArriveDestination() {
                    speak("已到达目的地", true);
                    isNavigating = false;
                    if (mAMapNavi != null) mAMapNavi.stopNavi();
                    if (naviListener != null) naviListener.onNavigationStateChange(false);
                }
                @Override public void onCalculateRouteSuccess(int[] ids) {
                    Log.d(TAG, "步行算路成功，启动步行导航");
                    mAMapNavi.startNavi(1);
                    isNavigating = true;
                    if (naviListener != null) naviListener.onNavigationStateChange(true);
                    speak("步行导航已启动，请按照语音提示行走", true);
                }
                @Override public void onCalculateRouteFailure(int error) {
                    Log.e(TAG, "步行算路失败，错误码：" + error);
                    speak("路线规划失败，错误码：" + error, true);
                    isNavigating = false;
                    if (naviListener != null) naviListener.onNavigationStateChange(false);
                }
                @Override public void onReCalculateRouteForYaw() {}
                @Override public void onReCalculateRouteForTrafficJam() {}
                @Override public void onArrivedWayPoint(int wayID) {}
                @Override public void onPlayRing(int type) {}
                @Override public void onGpsOpenStatus(boolean open) {
                    Log.d(TAG, "GPS状态: " + (open ? "已开启" : "已关闭"));
                    if (!open) speak("请打开GPS定位", true);
                }
                @Override public void onNaviInfoUpdate(NaviInfo naviInfo) {}
                @Override public void onServiceAreaUpdate(AMapServiceAreaInfo[] serviceAreaInfos) {}
                @Override public void onNaviRouteNotify(AMapNaviRouteNotifyData notifyData) {}
                @Override public void notifyParallelRoad(int i) {}
                @Override public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo[] aMapNaviTrafficFacilityInfos) {}
                @Override public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo aMapNaviTrafficFacilityInfo) {}
                @Override public void updateAimlessModeStatistics(AimLessModeStat aimLessModeStat) {}
                @Override public void updateAimlessModeCongestionInfo(AimLessModeCongestionInfo aimLessModeCongestionInfo) {}
                @Override public void onCalculateRouteSuccess(AMapCalcRouteResult aMapCalcRouteResult) {}
                @Override public void onCalculateRouteFailure(AMapCalcRouteResult aMapCalcRouteResult) {}
                @Override public void onGpsSignalWeak(boolean b) {}
                @Override public void showCross(AMapNaviCross aMapNaviCross) {}
                @Override public void hideCross() {}
                @Override public void showModeCross(AMapModelCross modelCross) {}
                @Override public void hideModeCross() {}
                @Override public void showLaneInfo(AMapLaneInfo laneInfo) {}
                @Override public void hideLaneInfo() {}
                @Override public void showLaneInfo(AMapLaneInfo[] aMapLaneInfos, byte[] bytes, byte[] bytes1) {}
                @Override public void updateCameraInfo(AMapNaviCameraInfo[] cameraInfos) {}
                @Override public void updateIntervalCameraInfo(AMapNaviCameraInfo startCameraInfo, AMapNaviCameraInfo endCameraInfo, int status) {}
            });
        } catch (Exception e) {
            Log.e(TAG, "高德导航初始化失败", e);
            if (naviListener != null) naviListener.onError("导航初始化失败：" + e.getMessage());
        }
    }

    private void doCalculateRoute(NaviLatLng endPoint) {
        try {
            NaviLatLng startPoint;
            AMapLocation lastLoc = mLocationClient != null ? mLocationClient.getLastKnownLocation() : null;
            if (lastLoc != null && lastLoc.getErrorCode() == 0) {
                startPoint = new NaviLatLng(lastLoc.getLatitude(), lastLoc.getLongitude());
            } else {
                startPoint = new NaviLatLng(39.908867, 116.397378);
            }
            Log.d(TAG, "开始计算步行路线，起点: (" + startPoint.getLatitude() + "," + startPoint.getLongitude() + ") 终点: (" + endPoint.getLatitude() + "," + endPoint.getLongitude() + ")");
            mAMapNavi.calculateWalkRoute(startPoint, endPoint);
        } catch (Exception e) {
            Log.e(TAG, "步行算路请求失败", e);
            speak("路线规划失败", true);
        }
    }

    public void startNavigation(String destination) {
        if (mAMapNavi == null) {
            speak("导航引擎未就绪", true);
            return;
        }
        if (isNavigating) {
            speak("导航进行中，请先停止导航", true);
            return;
        }
        pendingDestination = destination;
        if (naviListener != null) naviListener.onStatusMessage("正在查询目的地...");
        speak("正在查询目的地", true);
        mainHandler.postDelayed(() -> {
            new Thread(() -> {
                try {
                    Log.d(TAG, "准备发起POI搜索请求，关键词: " + destination);
                    PoiSearch.Query query = new PoiSearch.Query(destination, "", currentCity);
                    query.setPageSize(5);
                    query.setPageNum(0);
                    poiSearch.setQuery(query);
                    poiSearch.searchPOIAsyn();
                } catch (Exception e) {
                    Log.e(TAG, "POI搜索请求异常", e);
                    mainHandler.post(() -> {
                        speak("目的地查询失败：" + e.getMessage(), true);
                        if (naviListener != null) naviListener.onError("目的地查询失败：" + e.getMessage());
                        pendingDestination = null;
                        startWakeup();
                    });
                }
            }).start();
        }, 500);
    }

    public void stopNavigation() {
        if (mAMapNavi != null && isNavigating) {
            mAMapNavi.stopNavi();
            isNavigating = false;
            if (naviListener != null) {
                naviListener.onNavigationStateChange(false);
                naviListener.onStatusMessage("导航已停止");
            }
            speak("导航已停止", true);
        }
    }

    public String getCurrentLocation() { return currentLocation; }
    public boolean isNavigating() { return isNavigating; }

    @Override
    public void onLocationChanged(AMapLocation aMapLocation) {
        if (aMapLocation == null || aMapLocation.getErrorCode() != 0 || aMapLocation.getAccuracy() > 30.0f) {
            if (aMapLocation != null) {
                Log.w(TAG, "丢弃劣质定位 | 精度:" + aMapLocation.getAccuracy() + "米 | 错误码:" + aMapLocation.getErrorCode());
            }
            if (aMapLocation != null && locationRetryCount < MAX_LOCATION_RETRY) {
                locationRetryCount++;
                Log.w(TAG, "定位失败，尝试重启定位服务 (" + locationRetryCount + "/" + MAX_LOCATION_RETRY + ")");
                restartLocation();
            }
            return;
        }
        lastLocationTime = System.currentTimeMillis();
        locationRetryCount = 0;
        String province = aMapLocation.getProvince();
        String city = aMapLocation.getCity();
        String district = aMapLocation.getDistrict();
        String street = aMapLocation.getStreet();
        String streetNum = aMapLocation.getStreetNum();
        StringBuilder locationBuilder = new StringBuilder();
        if (province != null && !province.isEmpty()) {
            if (city != null && !city.isEmpty() && !province.equals(city)) {
                locationBuilder.append(province).append(city);
            } else {
                locationBuilder.append(province);
            }
        } else if (city != null && !city.isEmpty()) {
            locationBuilder.append(city);
        }
        if (district != null && !district.isEmpty()) locationBuilder.append(district);
        if (street != null && !street.isEmpty()) locationBuilder.append(street);
        if (streetNum != null && !streetNum.isEmpty()) locationBuilder.append(streetNum);
        currentLocation = locationBuilder.toString();
        currentCity = city;
        float accuracy = aMapLocation.getAccuracy();
        Log.d(TAG, "✅ 定位成功：" + currentLocation + "，精度：" + accuracy + "米，城市：" + currentCity);
        if (naviListener != null) naviListener.onLocationUpdate(currentLocation, accuracy);
    }

    private void initASR() {
        Log.d(TAG, "========== 初始化 SparkChain 语音听写 ==========");
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ 无录音权限");
            speak("请在设置中开启麦克风权限后重试", true);
            return;
        }
        if (!sparkChainInitialized) {
            Log.e(TAG, "❌ SparkChain未初始化成功");
            speak("语音引擎未就绪，请稍后再试", true);
            return;
        }
        mAsr = new ASR();
        mAsr.language("zh_cn");
        mAsr.domain("iat");
        mAsr.accent("mandarin");
        mAsr.vinfo(true);
        mAsr.ptt(true);
        mAsr.vadEos(5000);
        mAsr.registerCallbacks(new AsrCallbacks() {
            @Override
            public void onResult(ASR.ASRResult result, Object usrTag) {
                String text = result.getBestMatchText();
                int status = result.getStatus();
                Log.d(TAG, "onResult: text=" + text + ", status=" + status);
                if (status == 2 && text != null && !text.isEmpty()) {
                    listenTimeoutHandler.removeCallbacks(listenTimeoutRunnable);
                    stopAsrRecording();
                    isListening = false;
                    stopWakeup();
                    handleCommand(text);
                    mainHandler.postDelayed(() -> {
                        resetASROnly();
                        startWakeup();
                    }, 500);
                }
            }
            @Override
            public void onError(ASR.ASRError error, Object usrTag) {
                listenTimeoutHandler.removeCallbacks(listenTimeoutRunnable);
                isListening = false;
                stopAsrRecording();
                Log.e(TAG, "识别错误，错误码：" + error.getCode() + "，信息：" + error.getErrMsg());
                speak("识别失败，请重试", true);
                resetASROnly();
            }
        });
    }

    public void startListen() {
        if (isListening || mAsr == null) return;
        if (!sparkChainInitialized) {
            Log.e(TAG, "❌ SparkChain未初始化成功");
            return;
        }
        if (!isNetworkAvailable()) {
            Log.e(TAG, "❌ 网络不可用，无法启动听写");
            speak("网络不可用，请检查网络连接", true);
            startWakeup();
            return;
        }
        stopWakeup();
        if (!requestAudioFocus()) {
            Log.e(TAG, "获取音频焦点失败，无法启动听写");
            startWakeup();
            return;
        }
        int ret = mAsr.start("voice_tag");
        if (ret != 0) {
            isListening = false;
            Log.e(TAG, "启动听写会话失败，错误码：" + ret);
            releaseAudioFocus();
            startWakeup();
            return;
        } else {
            isListening = true;
            startAsrRecording();
            Log.d(TAG, "▶️ SparkChain语音识别已启动");
            listenTimeoutHandler.removeCallbacks(listenTimeoutRunnable);
            listenTimeoutHandler.postDelayed(listenTimeoutRunnable, LISTEN_TIMEOUT);
        }
    }

    @SuppressLint("MissingPermission")
    private void startAsrRecording() {
        if (isAsrRecording) return;
        isAsrRecording = true;
        asrRecordingThread = new Thread(() -> {
            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            asrAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    CHANNEL_CONFIG, AUDIO_FORMAT, minBufferSize * 2);
            if (asrAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败");
                isAsrRecording = false;
                return;
            }
            try {
                asrAudioRecord.startRecording();
            } catch (SecurityException e) {
                Log.e(TAG, "录音权限被拒绝", e);
                showToast("录音权限被拒绝");
                isAsrRecording = false;
                asrAudioRecord.release();
                asrAudioRecord = null;
                return;
            }
            byte[] buffer = new byte[BUFFER_SIZE];
            while (isAsrRecording && isListening) {
                int read = asrAudioRecord.read(buffer, 0, buffer.length);
                if (read > 0 && shouldWrite.get() && isListening && mAsr != null) {
                    int ret = mAsr.write(buffer.clone());
                    if (ret != 0) {
                        Log.e(TAG, "ASR write 失败，错误码：" + ret);
                        if (ret == 18305) {
                            mainHandler.post(this::resetASROnly);
                        }
                        break;
                    }
                }
            }
            if (asrAudioRecord != null) {
                try { asrAudioRecord.stop(); } catch (Exception e) { Log.e(TAG, "停止录音异常", e); }
                asrAudioRecord.release();
                asrAudioRecord = null;
            }
            isAsrRecording = false;
        });
        asrRecordingThread.start();
    }

    private void stopAsrRecording() {
        isAsrRecording = false;
        if (asrRecordingThread != null) {
            try { asrRecordingThread.join(1000); } catch (InterruptedException e) { e.printStackTrace(); }
            asrRecordingThread = null;
        }
        if (asrAudioRecord != null) {
            try { asrAudioRecord.stop(); } catch (Exception e) { Log.e(TAG, "停止录音异常", e); }
            asrAudioRecord.release();
            asrAudioRecord = null;
        }
    }

    private void stopListenIfNeeded() {
        if (isListening && mAsr != null) {
            Log.d(TAG, "播报前停止听写");
            listenTimeoutHandler.removeCallbacks(listenTimeoutRunnable);
            isListening = false;
            stopAsrRecording();
            mAsr.stop(true);
            releaseAudioFocus();
            mainHandler.postDelayed(() -> startWakeup(), 500);
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    private void handleCommand(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String cleanText = text.replaceAll(WAKE_WORD, "").replaceAll("\\s+", "").trim();
        cleanText = cleanText.replaceAll("[。，？！、；：]", "");
        if (cleanText.isEmpty()) {
            Log.d(TAG, "指令为空（可能只有唤醒词），忽略");
            return;
        }
        if (cleanText.length() < 2) {
            Log.d(TAG, "指令过短，忽略: " + cleanText);
            return;
        }

        boolean hasValidKeyword = false;
        for (String keyword : VALID_KEYWORDS) {
            if (cleanText.contains(keyword)) {
                hasValidKeyword = true;
                break;
            }
        }
        if (!hasValidKeyword) {
            if (cleanText.length() > 2 && !cleanText.equals(WAKE_WORD) && !cleanText.isEmpty()) {
                Log.d(TAG, "指令未包含关键词，但可能是目的地，自动导航到：" + cleanText);
                speak("正在为您导航到：" + cleanText, true);
                startNavigation(cleanText);
                lastInvalidCommandTime = 0;
                return;
            } else {
                Log.d(TAG, "指令不包含有效关键词，忽略: " + cleanText);
                return;
            }
        }

        if (cleanText.equals(lastTtsText)) {
            Log.d(TAG, "识别到与TTS相同内容，忽略: " + cleanText);
            repeatTtsCount++;
            if (repeatTtsCount >= TTS_REPEAT_MAX) {
                Log.w(TAG, "连续多次识别到TTS内容，暂停听写3秒");
                stopListenIfNeeded();
                ttsFilterHandler.removeCallbacksAndMessages(null);
                ttsFilterHandler.postDelayed(() -> {
                    if (!isListening && !isWakeupActive) startListen();
                }, 3000);
                repeatTtsCount = 0;
            }
            return;
        } else {
            repeatTtsCount = 0;
        }

        Log.d(TAG, "处理指令：" + cleanText);

        // 求助指令
        if (cleanText.contains(CMD_HELP)) {
            speak("正在发送求助信号，请稍后", true);
            sendHelpRequestWithVoiceOption();
            lastInvalidCommandTime = 0;
            return;
        }

        // 停止导航
        if (cleanText.contains(CMD_STOP)) {
            speak("正在为您停止导航", true);
            stopNavigation();
            lastInvalidCommandTime = 0;
            return;
        }

        // 我在哪
        if (cleanText.contains(CMD_WHERE)) {
            speak("您当前的位置是：" + currentLocation, true);
            lastInvalidCommandTime = 0;
            return;
        }

        // 盲道查询
        if (cleanText.contains("盲道") && (cleanText.contains("哪里") || cleanText.contains("哪儿") || cleanText.contains("位置") || cleanText.contains("在哪"))){
            String blindInfo = (blindRoadProvider != null) ? blindRoadProvider.getBlindRoadInfo() : "盲道信息暂不可用";
            speak(blindInfo, true, false);
            lastInvalidCommandTime = 0;
            return;
        }

        // 导航到目的地
        if (cleanText.contains(CMD_NAVI_PREFIX) || cleanText.startsWith("去") || cleanText.startsWith("到")
                || (cleanText.contains("路线") && (cleanText.contains("到") || cleanText.contains("去")))) {
            Matcher matcher = NAVI_PATTERN.matcher(cleanText);
            String address = "";
            if (matcher.find()) {
                address = matcher.group(1).trim();
            } else if (cleanText.startsWith("去")) {
                address = cleanText.replaceFirst("^去", "").trim();
            } else if (cleanText.startsWith("到")) {
                address = cleanText.replaceFirst("^到", "").trim();
            } else if (cleanText.contains("路线") && (cleanText.contains("到") || cleanText.contains("去"))) {
                int idx = Math.max(cleanText.indexOf("到"), cleanText.indexOf("去"));
                if (idx != -1) {
                    address = cleanText.substring(idx + 1).replace("路线", "").trim();
                } else {
                    address = cleanText.replace("路线", "").trim();
                }
            }
            if (!address.isEmpty()) {
                speak("正在为您导航到：" + address, true);
                startNavigation(address);
                lastInvalidCommandTime = 0;
                return;
            } else {
                if (System.currentTimeMillis() - lastInvalidCommandTime < INVALID_COMMAND_COOLDOWN) {
                    Log.d(TAG, "无效指令冷却期内，忽略播报: " + cleanText);
                } else {
                    speak("没听清目的地，请说完整指令", true);
                    lastInvalidCommandTime = System.currentTimeMillis();
                }
                return;
            }
        }

        if (System.currentTimeMillis() - lastInvalidCommandTime < INVALID_COMMAND_COOLDOWN) {
            Log.d(TAG, "无效指令冷却期内，忽略播报: " + cleanText);
        } else {
            speak("指令格式不对，请说：导航到+目的地", true);
            lastInvalidCommandTime = System.currentTimeMillis();
        }
    }

    public void speak(String msg) {
        speak(msg, false);
    }

    public void speak(String msg, boolean vibrate) {
        speak(msg, vibrate, true);
    }

    public void speak(String msg, boolean vibrate, boolean stopListening) {
        if (tts == null || msg == null || msg.isEmpty()) return;
        if (vibrate && vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(200);
        }
        mainHandler.post(() -> {
            if (stopListening) {
                stopListenIfNeeded();
            }
            ttsQueue.offer(msg);
            if (!isSpeaking) {
                processNextSpeak();
            }
        });
    }

    private void processNextSpeak() {
        if (tts == null) return;
        if (ttsQueue.isEmpty()) {
            isSpeaking = false;
            return;
        }
        isSpeaking = true;
        String msg = ttsQueue.poll();
        lastTtsText = msg;
        String utteranceId = "voice_navi_" + System.currentTimeMillis();
        tts.stop();
        if (!requestAudioFocus()) {
            Log.e(TAG, "TTS获取音频焦点失败");
            mainHandler.postDelayed(this::processNextSpeak, 500);
            return;
        }
        isTtsSpeaking.set(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        } else {
            HashMap<String, String> params = new HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            tts.speak(msg, TextToSpeech.QUEUE_FLUSH, params);
        }
        Log.d(TAG, "TTS 播报: " + msg + ", utteranceId=" + utteranceId);
    }

    private boolean requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int result = audioManager.requestAudioFocus(audioFocusRequest);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            int result = audioManager.requestAudioFocus(audioFocusChangeListener,
                    AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show());
    }

    private void setupTTSListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    Log.d(TAG, "TTS onStart: " + utteranceId);
                    shouldWrite.set(false);
                }
                @Override
                public void onDone(String utteranceId) {
                    Log.d(TAG, "TTS onDone: " + utteranceId);
                    isTtsSpeaking.set(false);
                    releaseAudioFocus();
                    mainHandler.postDelayed(() -> shouldWrite.set(true), 500);
                    processNextSpeak();
                    if (pendingStartListen && utteranceId != null && utteranceId.startsWith("voice_navi_")) {
                        Log.d(TAG, "唤醒提示播报完成，启动听写");
                        pendingStartListen = false;
                        stopWakeup();
                        mainHandler.postDelayed(() -> startListen(), 500);
                    } else if (!isListening && !isWakeupActive) {
                        mainHandler.postDelayed(() -> {
                            if (!isListening && !isWakeupActive) {
                                Log.d(TAG, "TTS播报后唤醒未激活，尝试启动");
                                startWakeup();
                            }
                        }, 300);
                    }
                }
                @Override
                public void onError(String utteranceId) {
                    Log.e(TAG, "TTS onError: " + utteranceId);
                    isTtsSpeaking.set(false);
                    releaseAudioFocus();
                    shouldWrite.set(true);
                    processNextSpeak();
                    if (pendingStartListen) {
                        pendingStartListen = false;
                        stopWakeup();
                        mainHandler.postDelayed(() -> startListen(), 500);
                    }
                }
            });
        } else {
            tts.setOnUtteranceCompletedListener(utteranceId -> {
                Log.d(TAG, "TTS onUtteranceCompleted: " + utteranceId);
                isTtsSpeaking.set(false);
                releaseAudioFocus();
                shouldWrite.set(false);
                mainHandler.postDelayed(() -> shouldWrite.set(true), 500);
                processNextSpeak();
                if (pendingStartListen && utteranceId != null && utteranceId.startsWith("voice_navi_")) {
                    pendingStartListen = false;
                    stopWakeup();
                    mainHandler.postDelayed(() -> startListen(), 500);
                }
            });
        }
    }

    private void startLocationGuard() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (System.currentTimeMillis() - lastLocationTime > LOCATION_TIMEOUT) {
                    Log.w(TAG, "定位长时间未更新，尝试重启");
                    restartLocation();
                }
                mainHandler.postDelayed(this, 3000);
            }
        }, 3000);
    }

    private void restartLocation() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "重启定位失败：权限丢失");
            return;
        }
        if (mLocationClient != null) {
            mLocationClient.stopLocation();
            mLocationClient.onDestroy();
        }
        try {
            mLocationClient = new AMapLocationClient(appContext);
            AMapLocationClientOption option = new AMapLocationClientOption();
            option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            option.setInterval(1000);
            option.setNeedAddress(true);
            option.setSensorEnable(true);
            option.setWifiScan(true);
            option.setLocationCacheEnable(false);
            option.setMockEnable(false);
            mLocationClient.setLocationOption(option);
            mLocationClient.setLocationListener(this);
            mLocationClient.startLocation();
        } catch (Exception e) {
            Log.e(TAG, "重启定位失败", e);
        }
    }

    //求助留言录音与上传功能
    private void sendHelpRequestWithVoiceOption() {
        new Thread(() -> {
            AMapLocation loc = mLocationClient.getLastKnownLocation();
            if (loc == null || loc.getErrorCode() != 0) {
                mainHandler.post(() -> speak("获取定位失败，求助未发送", true));
                return;
            }

            NetworkUtils.getInstance().sendHelpLocation(
                    loc.getLatitude(),
                    loc.getLongitude(),
                    loc.getAddress(),
                    loc.getCity(),
                    new NetworkUtils.HelpLocationCallback() {
                        @Override
                        public void onSuccess(String response) {
                            mainHandler.post(() -> {
                                speak("求助定位已发送，您可以在嘀声后留言，10秒后自动结束", true);
                                startHelpVoiceRecording();
                            });
                        }

                        @Override
                        public void onFailure(String error) {
                            mainHandler.post(() -> {
                                speak("求助发送失败，但您仍可留言", true);
                                startHelpVoiceRecording();
                            });
                        }
                    }
            );
        }).start();
    }

    @SuppressLint("MissingPermission")
    private void startHelpVoiceRecording() {
        if (isHelpRecording) return;

        stopListenIfNeeded();
        stopWakeup();

        // 初始化留言专用 ASR
        if (!sparkChainInitialized) {
            mainHandler.post(() -> speak("语音引擎未就绪", true));
            resumeNormalState();
            return;
        }
        helpAsr = new ASR();
        helpAsr.language("zh_cn");
        helpAsr.domain("iat");
        helpAsr.accent("mandarin");
        helpAsr.ptt(true);
        helpAsr.vadEos(5000);
        helpAsr.registerCallbacks(new AsrCallbacks() {
            @Override
            public void onResult(ASR.ASRResult result, Object usrTag) {
                if (result.getStatus() == 2) {
                    String text = result.getBestMatchText();
                    if (text != null && !text.isEmpty()) {
                        helpTranscriptBuilder.append(text);
                    }
                    helpAsrFinished = true;
                    Log.d(TAG, "留言实时识别结果: " + text);
                }
            }
            @Override
            public void onError(ASR.ASRError error, Object usrTag) {
                Log.e(TAG, "留言ASR错误: " + error.getErrMsg());
                helpAsrFinished = true;
            }
        });

        int ret = helpAsr.start("help_voice");
        if (ret != 0) {
            Log.e(TAG, "启动留言ASR失败: " + ret);
            mainHandler.post(() -> speak("语音识别启动失败", true));
            resumeNormalState();
            return;
        }

        final File cacheDir;
        File extCache = appContext.getExternalCacheDir();
        if (extCache != null) {
            cacheDir = extCache;
        } else {
            cacheDir = appContext.getCacheDir();
        }
        helpAudioFile = new File(cacheDir, "help_voice_" + System.currentTimeMillis() + ".wav");

        isHelpRecording = true;
        helpAsrFinished = false;
        helpTranscriptBuilder.setLength(0);

        helpRecordingThread = new Thread(() -> {
            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            helpAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    CHANNEL_CONFIG, AUDIO_FORMAT, minBufferSize * 2);
            if (helpAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord初始化失败");
                isHelpRecording = false;
                mainHandler.post(() -> speak("录音启动失败", true));
                return;
            }

            try {
                helpAudioRecord.startRecording();
            } catch (SecurityException e) {
                Log.e(TAG, "录音权限被拒绝", e);
                isHelpRecording = false;
                helpAudioRecord.release();
                mainHandler.post(() -> speak("录音权限被拒绝", true));
                return;
            }

            final File pcmTempFile = new File(cacheDir, "temp_" + System.currentTimeMillis() + ".pcm");
            final FileOutputStream[] fosHolder = new FileOutputStream[1];
            try {
                fosHolder[0] = new FileOutputStream(pcmTempFile);
            } catch (IOException e) {
                Log.e(TAG, "创建临时文件失败", e);
                isHelpRecording = false;
                helpAudioRecord.stop();
                helpAudioRecord.release();
                mainHandler.post(() -> speak("录音失败", true));
                return;
            }

            byte[] buffer = new byte[BUFFER_SIZE];
            long startTime = System.currentTimeMillis();

            while (isHelpRecording && (System.currentTimeMillis() - startTime) < HELP_RECORD_MAX_DURATION) {
                int read = helpAudioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    try {
                        fosHolder[0].write(buffer, 0, read);
                    } catch (IOException e) {
                        Log.e(TAG, "写入文件错误", e);
                        break;
                    }

                    if (helpAsr != null && !helpAsrFinished) {
                        byte[] chunk = new byte[read];
                        System.arraycopy(buffer, 0, chunk, 0, read);
                        int writeRet = helpAsr.write(chunk);
                        if (writeRet != 0) {
                            Log.e(TAG, "ASR写入失败: " + writeRet);
                        }
                    }
                }
            }

            try {
                fosHolder[0].close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            helpAudioRecord.stop();
            helpAudioRecord.release();
            helpAudioRecord = null;

            try {
                convertPcmToWav(pcmTempFile, helpAudioFile);
            } catch (IOException e) {
                Log.e(TAG, "PCM转WAV失败", e);
            }
            pcmTempFile.delete();

            if (helpAsr != null) {
                helpAsr.write(new byte[0]);
                long waitStart = System.currentTimeMillis();
                while (!helpAsrFinished && (System.currentTimeMillis() - waitStart) < 3000) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                }
                helpAsr.stop(true);
                helpAsr = null;
            }

            isHelpRecording = false;

            String finalTranscript = helpTranscriptBuilder.toString();
            mainHandler.post(() -> {
                if (!finalTranscript.isEmpty()) {
                    speak("识别结果：" + finalTranscript, false);
                }
                uploadHelpVoiceMessage(finalTranscript);
            });
        });
        helpRecordingThread.start();

        mainHandler.post(() -> speak("开始录音，请留言", false));
        if (vibrator != null) vibrator.vibrate(50);

        helpRecordHandler.postDelayed(() -> {
            if (isHelpRecording) {
                isHelpRecording = false;
            }
        }, HELP_RECORD_MAX_DURATION);
    }

    private void convertPcmToWav(File pcmFile, File wavFile) throws IOException {
        FileInputStream in = new FileInputStream(pcmFile);
        FileOutputStream out = new FileOutputStream(wavFile);

        long totalAudioLen = in.getChannel().size();
        long totalDataLen = totalAudioLen + 36;
        long longSampleRate = SAMPLE_RATE;
        int channels = 1;
        long byteRate = 16 * SAMPLE_RATE * channels / 8;

        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * 16 / 8); header[33] = 0;
        header[34] = 16; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        out.write(header);

        byte[] buffer = new byte[1024];
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
        in.close();
        out.close();
    }

    private void uploadHelpVoiceMessage(String transcript) {
        if (helpAudioFile == null || !helpAudioFile.exists()) {
            mainHandler.post(() -> speak("没有可上传的留言", true));
            resumeNormalState();
            return;
        }

        final String finalTranscript = transcript != null ? transcript : "";

        mainHandler.post(() -> speak("正在上传留言...", false));

        NetworkUtils.getInstance().uploadHelpVoice(helpAudioFile, finalTranscript, new NetworkUtils.VoiceUploadCallback() {
            @Override
            public void onSuccess(String response) {
                deleteHelpAudioFile();
                mainHandler.post(() -> speak("留言上传成功", true));
                resumeNormalState();
            }

            @Override
            public void onFailure(String error) {
                deleteHelpAudioFile();
                mainHandler.post(() -> speak("留言上传失败", true));
                resumeNormalState();
            }
        });
    }

    private void deleteHelpAudioFile() {
        if (helpAudioFile != null && helpAudioFile.exists()) {
            boolean deleted = helpAudioFile.delete();
            Log.d(TAG, "删除临时音频文件: " + helpAudioFile.getName() + ", 结果: " + deleted);
            helpAudioFile = null;
        }
    }

    private void resumeNormalState() {
        mainHandler.postDelayed(() -> {
            if (!isListening && !isWakeupActive) {
                startWakeup();
            }
        }, 500);
    }

    public void destroy() {
        mainHandler.removeCallbacksAndMessages(null);
        ttsFilterHandler.removeCallbacksAndMessages(null);
        listenTimeoutHandler.removeCallbacksAndMessages(null);
        helpRecordHandler.removeCallbacksAndMessages(null);
        if (wakeupHealthCheck != null) {
            mainHandler.removeCallbacks(wakeupHealthCheck);
        }

        if (isHelpRecording && helpAudioRecord != null) {
            isHelpRecording = false;
            try {
                helpAudioRecord.stop();
                helpAudioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "释放录音器异常", e);
            }
            helpAudioRecord = null;
        }
        if (helpAsr != null) {
            helpAsr.stop(true);
            helpAsr = null;
        }
        deleteHelpAudioFile();

        stopAsrRecording();
        stopWakeup();
        if (mAsr != null) {
            mAsr.stop(true);
            mAsr = null;
        }
        AiHelper.getInst().unInit();
        ttsQueue.clear();
        if (mLocationClient != null) {
            mLocationClient.stopLocation();
            mLocationClient.onDestroy();
        }
        if (mAMapNavi != null) {
            mAMapNavi.stopNavi();
            mAMapNavi.destroy();
        }
        releaseAudioFocus();
    }

    private final Runnable listenTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (isListening) {
                Log.w(TAG, "听写超时，自动结束");
                stopListenIfNeeded();
            }
        }
    };
}