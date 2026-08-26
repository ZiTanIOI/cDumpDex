package com.zitan.cdumpdex.reflection.ui;

import android.app.Activity;
import android.app.ActivityThread;
import android.content.Context;
import android.graphics.Typeface;
import android.util.ArrayMap;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zitan.cdumpdex.RetraceableVar;
import com.zitan.cdumpdex.reflection.VariableManager;

import java.util.List;

/**
 * 当前 Context 信息页面
 */
public class CurrentContextPage extends BasePageView {

    private VariableManager variableManager;

    public CurrentContextPage(Context context, NavigationStack navigationStack) {
        super(context, navigationStack);
        try {
            this.variableManager = VariableManager.getInstance(context);
        } catch (Exception e) {
            android.util.Log.e("CurrentContextPage", "Failed to get VariableManager", e);
        }
    }

    @Override
    public String getTitle() {
        return "当前 Context";
    }

    @Override
    protected void initContent(LinearLayout contentLayout) {
        // Context 信息卡片
        LinearLayout infoCard = createCard();

        // 类型
        TextView typeLabel = new TextView(context);
        typeLabel.setText("类型");
        typeLabel.setTextSize(12);
        typeLabel.setTextColor(COLOR_TEXT_SECONDARY);
        infoCard.addView(typeLabel);

        TextView typeView = createSelectableText(context.getClass().getName(), 14, COLOR_TEXT_PRIMARY);
        typeView.setTypeface(null, Typeface.BOLD);
        infoCard.addView(typeView);

        infoCard.addView(createDivider());

        // 包名
        TextView pkgLabel = new TextView(context);
        pkgLabel.setText("包名");
        pkgLabel.setTextSize(12);
        pkgLabel.setTextColor(COLOR_TEXT_SECONDARY);
        infoCard.addView(pkgLabel);

        TextView pkgView = createSelectableText(context.getPackageName(), 14, COLOR_TEXT_PRIMARY);
        infoCard.addView(pkgView);

        // Application
        try {
            android.app.Application app = (android.app.Application) context.getApplicationContext();
            infoCard.addView(createDivider());

            TextView appLabel = new TextView(context);
            appLabel.setText("Application");
            appLabel.setTextSize(12);
            appLabel.setTextColor(COLOR_TEXT_SECONDARY);
            infoCard.addView(appLabel);

            TextView appView = createSelectableText(app.getClass().getName(), 14, COLOR_TEXT_PRIMARY);
            infoCard.addView(appView);
        } catch (Exception ignored) {}

        // 当前 Activity
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            infoCard.addView(createDivider());

            TextView activityLabel = new TextView(context);
            activityLabel.setText("当前 Activity");
            activityLabel.setTextSize(12);
            activityLabel.setTextColor(COLOR_TEXT_SECONDARY);
            infoCard.addView(activityLabel);

            TextView activityView = createSelectableText(activity.getClass().getName(), 14, 0xFF4CAF50);
            activityView.setTypeface(null, Typeface.BOLD);
            infoCard.addView(activityView);

            TextView taskView = createSelectableText("TaskId: " + activity.getTaskId(), 12, COLOR_TEXT_SECONDARY);
            infoCard.addView(taskView);
        }

        contentLayout.addView(infoCard);

        // Activity 栈
        contentLayout.addView(createSectionTitle("Activity 栈"));
        contentLayout.addView(createExpandableItem("查看 Activity 栈", v -> showActivityStack()));

        // 操作
        contentLayout.addView(createSectionTitle("操作"));

        LinearLayout btnCard = createCard();
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button saveBtn = new Button(context);
        saveBtn.setText("保存 Context");
        saveBtn.setOnClickListener(v -> saveCurrentContext());
        btnRow.addView(saveBtn);

        Button copyBtn = new Button(context);
        copyBtn.setText("复制信息");
        copyBtn.setOnClickListener(v -> copyContextInfo());
        btnRow.addView(copyBtn);

        btnCard.addView(btnRow);
        contentLayout.addView(btnCard);

        // 已保存检查
        if (variableManager != null) {
            List<String> varNames = variableManager.getVariableNames();
            for (String varName : varNames) {
                RetraceableVar var = variableManager.getVariable(varName);
                if (var != null && var.getValue() == context) {
                    LinearLayout savedCard = createCard();
                    TextView savedText = createInfoText("当前 Context 已保存为变量: " + varName);
                    savedText.setTextColor(COLOR_PRIMARY);
                    savedCard.addView(savedText);
                    contentLayout.addView(savedCard);
                    break;
                }
            }
        }
    }

    private View createExpandableItem(String text, OnClickListener listener) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackgroundColor(COLOR_CARD);
        item.setPadding(dp(16), dp(14), dp(16), dp(14));
        item.setBackgroundResource(getSelectableBackground());

        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(15);
        textView.setTextColor(COLOR_TEXT_PRIMARY);
        textView.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        ));
        item.addView(textView);

        TextView arrow = new TextView(context);
        arrow.setText(">");
        arrow.setTextSize(14);
        arrow.setTextColor(COLOR_TEXT_SECONDARY);
        item.addView(arrow);

        item.setOnClickListener(listener);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(1));
        item.setLayoutParams(params);

        return item;
    }

    private void showActivityStack() {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(16), dp(16), dp(16));

        try {
            ActivityThread activityThread = ActivityThread.currentActivityThread();
            if (activityThread != null) {
                ArrayMap<?, ?> mActivities = com.zitan.cdumpdex.ReflectUtils.getMActivities(activityThread);

                if (mActivities != null && !mActivities.isEmpty()) {
                    int index = 1;
                    for (Object value : mActivities.values()) {
                        try {
                            Activity activity = com.zitan.cdumpdex.ReflectUtils.getActivity(value);
                            if (activity != null) {
                                LinearLayout item = new LinearLayout(context);
                                item.setOrientation(LinearLayout.VERTICAL);
                                item.setPadding(0, dp(8), 0, dp(8));

                                String text = index + ". " + activity.getClass().getName();

                                TextView numView = new TextView(context);
                                numView.setText(text);
                                numView.setTextSize(13);
                                numView.setTextIsSelectable(true);

                                if (activity == context) {
                                    numView.setTextColor(0xFF4CAF50);
                                    numView.setTypeface(null, Typeface.BOLD);
                                    numView.setText(text + " [当前]");
                                } else {
                                    numView.setTextColor(COLOR_TEXT_PRIMARY);
                                }

                                item.addView(numView);
                                container.addView(item);
                                index++;
                            }
                        } catch (Exception ignored) {}
                    }
                } else {
                    TextView emptyText = createInfoText("Activity 栈为空");
                    container.addView(emptyText);
                }
            }
        } catch (Exception e) {
            TextView errorText = createSelectableText("获取失败: " + e.getMessage(), 13, 0xFFE53935);
            container.addView(errorText);
        }

        android.widget.ScrollView scrollView = new android.widget.ScrollView(context);
        scrollView.addView(container);

        new android.app.AlertDialog.Builder(context)
            .setTitle("Activity 栈")
            .setView(scrollView)
            .setNegativeButton("关闭", null)
            .create().show();
    }

    private void saveCurrentContext() {
        if (variableManager == null) {
            showToast("变量管理器未初始化");
            return;
        }

        List<String> varNames = variableManager.getVariableNames();
        for (String varName : varNames) {
            RetraceableVar var = variableManager.getVariable(varName);
            if (var != null && var.getValue() == context) {
                showToast("当前 Context 已保存为: " + varName);
                return;
            }
        }

        RetraceableVar var = new RetraceableVar(null, context, RetraceableVar.VarSource.CONTEXT);
        String varName = variableManager.addVariable(var);
        showToast("已保存为: " + varName);

        // 刷新界面
        contentLayout.removeAllViews();
        initContent(contentLayout);
    }

    private void copyContextInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("类型: ").append(context.getClass().getName()).append("\n");
        sb.append("包名: ").append(context.getPackageName()).append("\n");

        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            sb.append("当前Activity: ").append(activity.getClass().getName()).append("\n");
        }

        copyToClipboard("Context信息", sb.toString());
    }
}
