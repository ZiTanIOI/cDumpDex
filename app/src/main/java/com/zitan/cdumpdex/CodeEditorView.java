package com.zitan.cdumpdex;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

import androidx.core.view.GestureDetectorCompat;

public class CodeEditorView extends View {

    // UI 组件与状态
    private TextPaint textPaint;
    private TextPaint linePaint;
    private TextPaint toolbarPaint;
    private GestureDetectorCompat gestureDetector;
    private ScaleGestureDetector scaleDetector;
    private float scaleFactor = 1.0f;
    private float offsetX = 0f, offsetY = 0f;
    private float lastTouchX, lastTouchY;

    // 工具栏配置
    private static final int TOOLBAR_HEIGHT = 120;
    private static final int LINE_NUMBER_WIDTH = 160;
    private List<String> toolbarItems = List.of("热修复", "转Java", "关闭页面");
    private int selectedToolIndex = -1;

    // 模拟文本内容
    private List<String> codeLines = new ArrayList<>();

    public CodeEditorView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        // 初始化画笔
        textPaint = new TextPaint();
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(48);
        textPaint.setAntiAlias(true);

        linePaint = new TextPaint();
        linePaint.setColor(Color.parseColor("#DDDDDD"));
        linePaint.setTextSize(48);
        linePaint.setTextAlign(Paint.Align.RIGHT);
        linePaint.setAntiAlias(true);

        toolbarPaint = new TextPaint();
        toolbarPaint.setColor(Color.WHITE);
        toolbarPaint.setTextSize(40);
        toolbarPaint.setTextAlign(Paint.Align.CENTER);
        toolbarPaint.setAntiAlias(true);

        // 初始化手势检测器
        gestureDetector = new GestureDetectorCompat(context, new MyGestureListener());
        scaleDetector = new ScaleGestureDetector(context, new MyScaleListener());

        // 模拟一些文本数据
        for (int i = 1; i <= 500; i++) {
            codeLines.add("这是第 " + i + " 行代码示例。Lorem ipsum dolor sit amet...");
        }

        // 设置背景为白色
        setBackgroundColor(Color.WHITE);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        int action = event.getAction();
        float x = event.getX();
        float y = event.getY();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = x;
                lastTouchY = y;
                break;
            case MotionEvent.ACTION_MOVE:
                // 处理拖拽
                float deltaX = x - lastTouchX;
                float deltaY = y - lastTouchY;

                // 仅在缩放后允许拖动
                if (scaleFactor > 1.0f) {
                    offsetX += deltaX;
                    offsetY += deltaY;
                    // 限制边界
                    limitBounds();
                }
                lastTouchX = x;
                lastTouchY = y;
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
                // 点击工具栏逻辑
                if (y < TOOLBAR_HEIGHT) {
                    int itemWidth = getWidth() / toolbarItems.size();
                    int index = (int) (x / itemWidth);
                    handleToolbarClick(index);
                }
                break;
        }
        return true;
    }

    private void handleToolbarClick(int index) {
        if (index >= 0 && index < toolbarItems.size()) {
            selectedToolIndex = index;
            // 这里触发功能逻辑
            switch (index) {
                case 0:
                    onHotFix();
                    break;
                case 1:
                    onConvertToJava();
                    break;
                case 2:
                    ((Activity)getContext()).finish(); // 关闭页面
                    break;
            }
            invalidate();
        }
    }

    private void onHotFix() {
        // 实现热修复逻辑
        System.out.println("执行热修复");
    }

    private void onConvertToJava() {
        // 实现转换Java逻辑
        System.out.println("转换为Java");
    }

    private void limitBounds() {
        // 获取文本区域的总大小
        Rect textBounds = new Rect();
        String sampleText = "示例文本";
        textPaint.getTextBounds(sampleText, 0, sampleText.length(), textBounds);
        int lineHeight = textBounds.height() + 10;
        int totalTextHeight = codeLines.size() * lineHeight;
        int totalTextWidth = (int) (getWidth() * 1.5f); // 模拟长文本

        // 计算缩放后的尺寸
        int scaledHeight = (int) (totalTextHeight * scaleFactor);
        int scaledWidth = (int) (totalTextWidth * scaleFactor);

        // 限制边界
        int viewWidth = getWidth();
        int viewHeight = getHeight() - TOOLBAR_HEIGHT;

        float maxX = Math.max(0, (scaledWidth - viewWidth) / scaleFactor);
        float maxY = Math.max(0, (scaledHeight - viewHeight) / scaleFactor);

        offsetX = Math.max(-maxX, Math.min(offsetX, maxX));
        offsetY = Math.max(-maxY, Math.min(offsetY, maxY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();

        // 1. 绘制工具栏 (顶部)
        canvas.drawRect(0, 0, width, TOOLBAR_HEIGHT, new Paint() {{
            setColor(Color.parseColor("#3F51B5")); // 蓝色背景
        }});

        // 绘制工具栏文字
        int itemWidth = width / toolbarItems.size();
        for (int i = 0; i < toolbarItems.size(); i++) {
            float cx = itemWidth * (i + 0.5f);
            float cy = TOOLBAR_HEIGHT / 2f + getTextHeight(toolbarPaint) / 2f - 10;
            canvas.drawText(toolbarItems.get(i), cx, cy, toolbarPaint);

            // 绘制选中状态
            if (selectedToolIndex == i) {
                Paint indicator = new Paint();
                indicator.setColor(Color.parseColor("#FF4081"));
                indicator.setStrokeWidth(8);
                canvas.drawLine(cx - 40, TOOLBAR_HEIGHT - 10, cx + 40, TOOLBAR_HEIGHT - 10, indicator);
            }
        }

        // 2. 保存画布状态以应用缩放和平移
        canvas.save();
        canvas.translate(offsetX * scaleFactor, offsetY * scaleFactor + TOOLBAR_HEIGHT);
        canvas.scale(scaleFactor, scaleFactor);

        // 3. 绘制行号栏 (左侧)
        canvas.drawRect(-LINE_NUMBER_WIDTH, 0, 0, height, new Paint() {{
            setColor(Color.parseColor("#F5F5F5")); // 浅灰背景
            setStrokeWidth(2);
            setColor(Color.parseColor("#CCCCCC"));
        }});

        // 绘制行号
        Rect bounds = new Rect();
        int lineHeight = getTextHeight(textPaint) + 10;
        int startY = (int) (-offsetY * scaleFactor);
        int startLine = Math.max(0, startY / lineHeight);

        for (int i = startLine; i < codeLines.size(); i++) {
            String lineNumber = String.valueOf(i + 1);
            linePaint.getTextBounds(lineNumber, 0, lineNumber.length(), bounds);
            float y = i * lineHeight + lineHeight;

            if (y > height + TOOLBAR_HEIGHT) break;

            canvas.drawText(lineNumber, -20, y, linePaint);
        }

        // 4. 绘制代码文本
        Paint textBackground = new Paint();
        textBackground.setColor(Color.WHITE);
        canvas.drawRect(0, 0, width * 2, height * 2, textBackground);

        for (int i = startLine; i < codeLines.size(); i++) {
            float y = i * lineHeight + lineHeight;
            if (y > height + TOOLBAR_HEIGHT) break;
            canvas.drawText(codeLines.get(i), 20, y, textPaint);
        }

        canvas.restore();
    }

    private int getTextHeight(TextPaint paint) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        return (int) (fm.descent - fm.ascent);
    }

    private class MyScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            scaleFactor *= detector.getScaleFactor();
            scaleFactor = Math.max(0.5f, Math.min(scaleFactor, 5.0f)); // 限制缩放范围
            limitBounds();
            invalidate();
            return true;
        }
    }

    private class MyGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            // 我们在 onTouchEvent 中处理移动，这里可以留空或用于其他逻辑
            return false;
        }
    }
}