package com.example.cameraupload;

public class DetectionResult {
    // 目标类别
    private String label;
    // 置信度
    private float confidence;
    // 目标位置坐标
    private int left;   // 左上角x
    private int top;    // 左上角y
    private int right;  // 右下角x
    private int bottom; // 右下角y
    //目标与摄像头的距离（单位：米）
    private float distance;

    public DetectionResult(String label, float confidence, int left, int top, int right, int bottom) {
        this.label = label;
        this.confidence = confidence;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        // 默认距离设为-1（表示未检测/未知）
        this.distance = -1f;
    }

    public DetectionResult(String label, float confidence, int left, int top, int right, int bottom, float distance) {
        this.label = label;
        this.confidence = confidence;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.distance = distance;
    }

    public String getLabel() {
        return label;
    }

    public float getConfidence() {
        return confidence;
    }

    public int getLeft() {
        return left;
    }

    public int getTop() {
        return top;
    }

    public int getRight() {
        return right;
    }

    public int getBottom() {
        return bottom;
    }

    // 距离的Getter方法
    public float getDistance() {
        return distance;
    }

    // 距离的Setter方法
    public void setDistance(float distance) {
        this.distance = distance;
    }

    // 辅助方法：计算目标宽度
    public int getWidth() {
        return right - left;
    }

    // 辅助方法：计算目标高度
    public int getHeight() {
        return bottom - top;
    }

}