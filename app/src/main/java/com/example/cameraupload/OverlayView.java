package com.example.cameraupload;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

public class OverlayView extends View {
    private final Paint boxPaint;         // 检测框画笔
    private final Paint textPaint;        // 标签文字画笔
    private final List<DetectionResult> results;  // 检测结果列表
    private boolean alertMode;            // 警报模式（闪烁效果）
    private long alertStartTime;          // 警报开始时间

    // 缩放比例
    private float scaleX = 1f;
    private float scaleY = 1f;

    // 类别颜色映射
    private final HashMap<String, Integer> labelColors;
    private final Random random;

    // 构造方法
    public OverlayView(Context context) {
        this(context, null);
    }

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // 初始化检测框画笔
        boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4f);
        boxPaint.setAntiAlias(true);

        // 初始化文字画笔
        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(32f);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setAntiAlias(true);
        textPaint.setShadowLayer(4f, 0f, 2f, Color.BLACK); // 文字阴影

        results = new ArrayList<>();
        alertMode = false;
        alertStartTime = 0;

        labelColors = new HashMap<>();
        random = new Random(42);
    }

    public void setScale(float scaleX, float scaleY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }

    public void setResults(List<DetectionResult> results) {
        this.results.clear();
        if (results != null) {
            this.results.addAll(results);
        }
        invalidate(); // 触发重绘
    }

    public void setAlertMode(boolean alertMode) {
        this.alertMode = alertMode;
        if (alertMode) {
            alertStartTime = System.currentTimeMillis();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (DetectionResult result : results) {
            int previewLeft = (int) (result.getLeft() * scaleX);
            int previewTop = (int) (result.getTop() * scaleY);
            int previewRight = (int) (result.getRight() * scaleX);
            int previewBottom = (int) (result.getBottom() * scaleY);
            int color = labelColors.getOrDefault(result.getLabel(), generateColorForLabel(result.getLabel()));
            boxPaint.setColor(color);
            canvas.drawRect(previewLeft, previewTop, previewRight, previewBottom, boxPaint);
            String labelText = result.getLabel() + " " + String.format("%.1f%%", result.getConfidence() * 100);
            float textY = (previewTop > 32) ? (previewTop - 8) : 32; // 避免文字超出屏幕
            canvas.drawText(labelText, previewLeft, textY, textPaint);
        }
        if (alertMode) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - alertStartTime > 3000) {
                alertMode = false;
            } else {
                int alpha = ((currentTime / 200) % 2 == 0) ? 100 : 0;
                boxPaint.setColor(Color.RED);
                boxPaint.setStyle(Paint.Style.FILL);
                boxPaint.setAlpha(alpha);
                canvas.drawRect(0, 0, getWidth(), getHeight(), boxPaint);
                invalidate();
            }
        }
    }
    private int generateColorForLabel(String label) {
        int color = Color.rgb(
                random.nextInt(150) + 50,   // R（50~200）
                random.nextInt(150) + 50,   // G（50~200）
                random.nextInt(150) + 50    // B（50~200）
        );
        labelColors.put(label, color);
        return color;
    }
}