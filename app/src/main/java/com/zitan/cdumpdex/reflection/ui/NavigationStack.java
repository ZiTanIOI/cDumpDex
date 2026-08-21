package com.zitan.cdumpdex.reflection.ui;

import android.content.Context;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;

import java.util.Stack;

/**
 * 页面导航栈
 * 管理页面的前进、后退和生命周期
 */
public class NavigationStack {

    private final Context context;
    private final FrameLayout container;
    private final Stack<Page> pageStack = new Stack<>();
    private OnStackChangedListener stackChangedListener;

    // 动画时长
    private static final int ANIM_DURATION = 200;

    public NavigationStack(Context context, FrameLayout container) {
        this.context = context;
        this.container = container;
    }

    /**
     * 设置栈变化监听器
     */
    public void setOnStackChangedListener(OnStackChangedListener listener) {
        this.stackChangedListener = listener;
    }

    /**
     * 推入新页面
     */
    public void push(Page page) {
        // 隐藏当前页面
        if (!pageStack.isEmpty()) {
            Page currentPage = pageStack.peek();
            currentPage.onHide();
            currentPage.getView().setVisibility(android.view.View.GONE);
        }

        // 添加新页面
        pageStack.push(page);
        container.addView(page.getView());
        page.onShow();

        // 播放进入动画
        playEnterAnimation(page.getView());

        notifyStackChanged();
    }

    /**
     * 推入新页面（替换当前页面，当前页面会被移除）
     */
    public void replace(Page page) {
        if (!pageStack.isEmpty()) {
            Page currentPage = pageStack.pop();
            currentPage.onHide();
            currentPage.onDestroy();
            container.removeView(currentPage.getView());
        }

        pageStack.push(page);
        container.addView(page.getView());
        page.onShow();

        notifyStackChanged();
    }

    /**
     * 返回上一页
     * @return true 表示成功返回，false 表示已在根页面
     */
    public boolean goBack() {
        if (pageStack.size() <= 1) {
            return false;
        }

        // 移除当前页面
        Page currentPage = pageStack.pop();
        currentPage.onHide();
        currentPage.onDestroy();

        // 播放退出动画后移除视图
        playExitAnimation(currentPage.getView(), () -> {
            container.removeView(currentPage.getView());
        });

        // 显示上一页面
        if (!pageStack.isEmpty()) {
            Page prevPage = pageStack.peek();
            prevPage.getView().setVisibility(android.view.View.VISIBLE);
            prevPage.onShow();
        }

        notifyStackChanged();
        return true;
    }

    /**
     * 返回到根页面
     */
    public void popToRoot() {
        while (pageStack.size() > 1) {
            Page page = pageStack.pop();
            page.onHide();
            page.onDestroy();
            container.removeView(page.getView());
        }

        if (!pageStack.isEmpty()) {
            Page root = pageStack.peek();
            root.getView().setVisibility(android.view.View.VISIBLE);
            root.onShow();
        }

        notifyStackChanged();
    }

    /**
     * 清空所有页面
     */
    public void clear() {
        for (Page page : pageStack) {
            page.onHide();
            page.onDestroy();
            container.removeView(page.getView());
        }
        pageStack.clear();
        notifyStackChanged();
    }

    /**
     * 获取当前页面
     */
    public Page getCurrentPage() {
        return pageStack.isEmpty() ? null : pageStack.peek();
    }

    /**
     * 获取栈大小
     */
    public int getSize() {
        return pageStack.size();
    }

    /**
     * 检查是否可以返回
     */
    public boolean canGoBack() {
        return pageStack.size() > 1;
    }

    /**
     * 处理返回键
     * @return true 表示已处理
     */
    public boolean onBackPressed() {
        Page current = getCurrentPage();
        if (current != null && current.onBackPressed()) {
            return true;
        }
        return goBack();
    }

    /**
     * 播放进入动画
     */
    private void playEnterAnimation(android.view.View view) {
        TranslateAnimation anim = new TranslateAnimation(
            Animation.RELATIVE_TO_SELF, 1.0f,
            Animation.RELATIVE_TO_SELF, 0.0f,
            Animation.RELATIVE_TO_SELF, 0.0f,
            Animation.RELATIVE_TO_SELF, 0.0f
        );
        anim.setDuration(ANIM_DURATION);
        view.startAnimation(anim);
    }

    /**
     * 播放退出动画
     */
    private void playExitAnimation(android.view.View view, Runnable onAnimationEnd) {
        TranslateAnimation anim = new TranslateAnimation(
            Animation.RELATIVE_TO_SELF, 0.0f,
            Animation.RELATIVE_TO_SELF, 1.0f,
            Animation.RELATIVE_TO_SELF, 0.0f,
            Animation.RELATIVE_TO_SELF, 0.0f
        );
        anim.setDuration(ANIM_DURATION);
        anim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}

            @Override
            public void onAnimationEnd(Animation animation) {
                if (onAnimationEnd != null) {
                    onAnimationEnd.run();
                }
            }

            @Override
            public void onAnimationRepeat(Animation animation) {}
        });
        view.startAnimation(anim);
    }

    /**
     * 通知栈变化
     */
    private void notifyStackChanged() {
        if (stackChangedListener != null) {
            stackChangedListener.onStackChanged(pageStack.size(), getCurrentPage());
        }
    }

    /**
     * 栈变化监听器
     */
    public interface OnStackChangedListener {
        void onStackChanged(int stackSize, Page currentPage);
    }
}
