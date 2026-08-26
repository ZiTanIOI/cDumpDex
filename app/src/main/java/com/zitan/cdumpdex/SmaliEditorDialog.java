package com.zitan.cdumpdex;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Smali代码编辑对话框
 * 用于显示和编辑smali代码，支持热修复和转Java功能
 */
public class SmaliEditorDialog {

    private final Context context;
    private AlertDialog dialog;
    private EditText codeEditText;
    private TextView titleTextView;
    private String className;
    private String originalSmali;
    private String currentSmali;
    private File dexInjectPath;
    private int apiLevel = 30;  // 编译 smali 为 dex 时使用的 API 级别(由宿主传入设备实际值)
    private OnHotFixListener hotFixListener;
    private OnDecompileJavaListener decompileJavaListener;
    private boolean isModified = false;

    public interface OnHotFixListener {
        void onHotFix(String className, String smaliCode);
    }

    public interface OnDecompileJavaListener {
        void onDecompileJava(String className);
    }

    public SmaliEditorDialog(Context context) {
        this.context = context;
    }

    public SmaliEditorDialog setClassName(String className) {
        this.className = className;
        return this;
    }

    public SmaliEditorDialog setSmaliCode(String smaliCode) {
        this.originalSmali = smaliCode;
        this.currentSmali = smaliCode;
        if (codeEditText != null) {
            codeEditText.setText(smaliCode);
        }
        return this;
    }

    public SmaliEditorDialog setDexInjectPath(File path) {
        this.dexInjectPath = path;
        return this;
    }

    public SmaliEditorDialog setApiLevel(int apiLevel) {
        this.apiLevel = apiLevel;
        return this;
    }

    public SmaliEditorDialog setOnHotFixListener(OnHotFixListener listener) {
        this.hotFixListener = listener;
        return this;
    }

    public SmaliEditorDialog setOnDecompileJavaListener(OnDecompileJavaListener listener) {
        this.decompileJavaListener = listener;
        return this;
    }

    public void show() {
        createDialog();
        dialog.show();
        // 设置全屏
        WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        dialog.getWindow().setAttributes(params);
    }

    private void createDialog() {
        LinearLayout mainLayout = new LinearLayout(context);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        // 标题栏
        LinearLayout titleBar = createTitleBar();
        mainLayout.addView(titleBar);

        // 代码编辑区域
        ScrollView scrollView = new ScrollView(context);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f));
        scrollView.setFillViewport(true);

        codeEditText = new EditText(context);
        codeEditText.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        codeEditText.setTypeface(Typeface.MONOSPACE);
        codeEditText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        codeEditText.setGravity(Gravity.TOP | Gravity.START);
        codeEditText.setPadding(16, 16, 16, 16);
        codeEditText.setText(currentSmali != null ? currentSmali : "");
        codeEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                currentSmali = s.toString();
                isModified = !currentSmali.equals(originalSmali);
                updateTitle();
            }
        });

        scrollView.addView(codeEditText);
        mainLayout.addView(scrollView);

        // 工具栏
        LinearLayout toolBar = createToolBar();
        mainLayout.addView(toolBar);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(mainLayout);
        builder.setCancelable(true);
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                if (isModified) {
                    // 可以在这里添加未保存提示
                }
            }
        });

        dialog = builder.create();
    }

    private LinearLayout createTitleBar() {
        LinearLayout titleBar = new LinearLayout(context);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(16, 12, 16, 12);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        titleTextView = new TextView(context);
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleTextView.setTypeface(null, Typeface.BOLD);
        updateTitle();
        titleTextView.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f));

        titleBar.addView(titleTextView);
        return titleBar;
    }

    private void updateTitle() {
        if (titleTextView != null) {
            String title = "Smali: " + (className != null ? className : "Unknown");
            if (isModified) {
                title += " *";
            }
            titleTextView.setText(title);
        }
    }

    private LinearLayout createToolBar() {
        LinearLayout toolBar = new LinearLayout(context);
        toolBar.setOrientation(LinearLayout.HORIZONTAL);
        toolBar.setGravity(Gravity.CENTER);
        toolBar.setPadding(8, 8, 8, 8);
        toolBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 热修复按钮
        TextView btnHotFix = createButton("热修复", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onHotFixClick();
            }
        });
        toolBar.addView(btnHotFix);

        // 转Java按钮
        TextView btnToJava = createButton("转Java", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onToJavaClick();
            }
        });
        toolBar.addView(btnToJava);

        // 保存按钮
        TextView btnSave = createButton("保存", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSaveClick();
            }
        });
        toolBar.addView(btnSave);

        // 关闭按钮
        TextView btnClose = createButton("关闭", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCloseClick();
            }
        });
        toolBar.addView(btnClose);

        return toolBar;
    }

    private TextView createButton(String text, View.OnClickListener listener) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setPadding(24, 12, 24, 12);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(8, 0, 8, 0);
        button.setLayoutParams(params);
        return button;
    }

    private void onHotFixClick() {
        if (hotFixListener != null) {
            hotFixListener.onHotFix(className, currentSmali);
        } else {
            Toast.makeText(context, "热修复功能未配置", Toast.LENGTH_SHORT).show();
        }
    }

    private void onToJavaClick() {
        if (decompileJavaListener != null) {
            decompileJavaListener.onDecompileJava(className);
        } else {
            Toast.makeText(context, "Java反编译功能未配置", Toast.LENGTH_SHORT).show();
        }
    }

    private void onSaveClick() {
        if (className == null || currentSmali == null) {
            Toast.makeText(context, "没有可保存的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        if (dexInjectPath == null) {
            Toast.makeText(context, "保存失败: 输出目录未初始化", Toast.LENGTH_SHORT).show();
            return;
        }

        // 编译 smali 为 dex 并保存(后台线程, 编译耗时)
        final String smali = currentSmali;
        final String clsName = className;
        new Thread(() -> {
            try {
                String dexName = clsName.replace('.', '_') + "_" + System.currentTimeMillis() + ".dex";
                File outputDex = new File(dexInjectPath, dexName);
                boolean ok = new SmaliCompiler().setApiLevel(apiLevel)
                        .compileSmaliToDexFile(smali, clsName, outputDex);
                context.getMainExecutor().execute(() -> {
                    if (ok) {
                        originalSmali = smali;
                        isModified = false;
                        updateTitle();
                        Toast.makeText(context, "已编译保存: " + outputDex.getAbsolutePath(),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(context, "Smali编译失败，请检查语法 (API " + apiLevel + ")",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable e) {
                context.getMainExecutor().execute(() ->
                        Toast.makeText(context, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void onCloseClick() {
        if (isModified) {
            new AlertDialog.Builder(context)
                    .setTitle("提示")
                    .setMessage("代码已修改，是否放弃修改？")
                    .setPositiveButton("放弃", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dismiss();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            dismiss();
        }
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    public void setEditable(boolean editable) {
        if (codeEditText != null) {
            codeEditText.setEnabled(editable);
            codeEditText.setFocusable(editable);
            codeEditText.setFocusableInTouchMode(editable);
        }
    }

    public String getCurrentSmali() {
        return currentSmali;
    }

    public boolean isModified() {
        return isModified;
    }
}
