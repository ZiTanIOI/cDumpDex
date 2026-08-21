package com.zitan.cdumpdex.reflection.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.List;

/**
 * 反射功能主容器
 */
public class ReflectionLayout extends FrameLayout {

    private final NavigationStack navigationStack;
    private AlertDialog dialog;
    private ClassLoader classLoader;
    private List<ClassLoader> additionalClassLoaders;

    public ReflectionLayout(Context context) {
        super(context);
        navigationStack = new NavigationStack(context, this);

        setBackgroundColor(Color.WHITE);

        setLayoutParams(new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 重要：设置可获取焦点，以支持输入法
        setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        setFocusableInTouchMode(true);

        navigationStack.setOnStackChangedListener((stackSize, currentPage) -> {
            if (dialog != null && currentPage != null) {
                updateDialogTitle();
            }
        });
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public void setAdditionalClassLoaders(List<ClassLoader> additionalClassLoaders) {
        this.additionalClassLoaders = additionalClassLoaders;
    }

    public void setDialog(AlertDialog dialog) {
        this.dialog = dialog;
        updateDialogTitle();

        // 重要：设置输入法模式
        try {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                );
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private void updateDialogTitle() {
        if (dialog != null) {
            Page current = navigationStack.getCurrentPage();
            if (current != null) {
                String title = current.getTitle();
                if (navigationStack.getSize() > 1) {
                    title += " (" + navigationStack.getSize() + ")";
                }
                dialog.setTitle(title);
            }
        }
    }

    public NavigationStack getNavigationStack() {
        return navigationStack;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public List<ClassLoader> getAdditionalClassLoaders() {
        return additionalClassLoaders;
    }

    public void showMainMenu() {
        navigationStack.clear();
        MainPage mainPage = new MainPage(getContext(), navigationStack, classLoader, additionalClassLoaders);
        navigationStack.push(mainPage);
    }

    public boolean handleBackPress() {
        if (navigationStack.canGoBack()) {
            navigationStack.goBack();
            return true;
        }
        return false;
    }

    /**
     * 显示反射功能对话框
     */
    public static AlertDialog showReflectionDialog(Context context, ClassLoader classLoader) {
        return showReflectionDialog(context, classLoader, null);
    }

    /**
     * 显示反射功能对话框（支持多个 ClassLoader）
     */
    public static AlertDialog showReflectionDialog(Context context, ClassLoader classLoader, List<ClassLoader> additionalClassLoaders) {
        ReflectionLayout layout = new ReflectionLayout(context);
        layout.setClassLoader(classLoader);
        layout.setAdditionalClassLoaders(additionalClassLoaders);
        layout.showMainMenu();

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(layout);
        builder.setTitle("反射工具");

        builder.setNegativeButton("关闭", null);

        AlertDialog dialog = builder.create();
        layout.setDialog(dialog);

        // 重写按键监听
        dialog.setOnKeyListener((dialogInterface, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                return layout.handleBackPress();
            }
            return false;
        });

        dialog.show();

        // 确保对话框窗口属性正确
        try {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                );
                // 清除 FLAG_NOT_FOCUSABLE，允许输入法
                dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            }
        } catch (Exception e) {
            // ignore
        }

        return dialog;
    }
}
