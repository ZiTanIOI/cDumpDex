package com.zitan.cdumpdex.reflection.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zitan.cdumpdex.RetraceableVar;
import com.zitan.cdumpdex.reflection.InstanceScanner;
import com.zitan.cdumpdex.reflection.VariableManager;

import java.util.List;

/**
 * 实例扫描页面
 */
public class InstanceScanPage extends BasePageView {

    private final ClassLoader classLoader;
    private VariableManager variableManager;

    private EditText classNameInput;
    private LinearLayout resultContainer;
    private TextView statusText;

    public InstanceScanPage(Context context, NavigationStack navigationStack, ClassLoader classLoader) {
        super(context, navigationStack);
        this.classLoader = classLoader;
        try {
            this.variableManager = VariableManager.getInstance(context);
        } catch (Exception e) {
            android.util.Log.e("InstanceScanPage", "Failed to get VariableManager", e);
        }
    }

    @Override
    public String getTitle() {
        return "获取内存实例";
    }

    @Override
    protected void initContent(LinearLayout contentLayout) {
        // 说明卡片
        LinearLayout hintCard = createCard();
        TextView hint = createInfoText("通过 Hook 构造函数追踪实例，只能获取 Hook 后创建的实例");
        hintCard.addView(hint);
        contentLayout.addView(hintCard);

        // 输入类名扫描
        contentLayout.addView(createSectionTitle("按类名扫描"));

        LinearLayout inputCard = createCard();
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);

        classNameInput = new EditText(context);
        classNameInput.setHint("com.example.MyClass");
        classNameInput.setTextSize(14);
        classNameInput.setBackgroundResource(android.R.drawable.edit_text);
        classNameInput.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        ));
        classNameInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showSoftInput(v);
        });
        classNameInput.setOnClickListener(v -> showSoftInput(v));
        inputRow.addView(classNameInput);

        Button scanBtn = new Button(context);
        scanBtn.setText("扫描");
        scanBtn.setOnClickListener(v -> {
            hideSoftInput(classNameInput);
            scanByClassName();
        });
        inputRow.addView(scanBtn);

        inputCard.addView(inputRow);
        contentLayout.addView(inputCard);

        // 快速扫描
        contentLayout.addView(createSectionTitle("快速扫描"));

        LinearLayout quickScanContainer = new LinearLayout(context);
        quickScanContainer.setOrientation(LinearLayout.VERTICAL);
        contentLayout.addView(quickScanContainer);

        addQuickScanButton(quickScanContainer, "Activity 实例", "android.app.Activity");
        addQuickScanButton(quickScanContainer, "Fragment 实例", "android.app.Fragment", "androidx.fragment.app.Fragment");
        addQuickScanButton(quickScanContainer, "View 实例", "android.view.View");
        addQuickScanButton(quickScanContainer, "Dialog 实例", "android.app.Dialog");
        addQuickScanButton(quickScanContainer, "Service 实例", "android.app.Service");

        // 已 Hook 的类
        contentLayout.addView(createSectionTitle("已 Hook 的类"));

        Button viewHookedBtn = new Button(context);
        viewHookedBtn.setText("查看已 Hook 的类列表");
        viewHookedBtn.setOnClickListener(v -> showHookedClasses());
        contentLayout.addView(viewHookedBtn);

        // 状态
        statusText = new TextView(context);
        statusText.setTextSize(12);
        statusText.setTextColor(COLOR_TEXT_SECONDARY);
        statusText.setPadding(0, dp(8), 0, 0);
        contentLayout.addView(statusText);

        // 结果容器
        resultContainer = new LinearLayout(context);
        resultContainer.setOrientation(LinearLayout.VERTICAL);
        contentLayout.addView(resultContainer);
    }

    private void addQuickScanButton(LinearLayout container, String label, String... classNames) {
        Button btn = new Button(context);
        btn.setText(label);
        btn.setOnClickListener(v -> scanInstances(classNames));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(4));
        btn.setLayoutParams(params);

        container.addView(btn);
    }

    private void scanByClassName() {
        String className = classNameInput.getText().toString().trim();
        if (className.isEmpty()) {
            showToast("请输入类名");
            return;
        }
        scanInstances(className);
    }

    private void scanInstances(String... classNames) {
        resultContainer.removeAllViews();
        statusText.setText("正在扫描...");

        new Thread(() -> {
            int totalCount = 0;

            for (String className : classNames) {
                if (!InstanceScanner.isClassHooked(className)) {
                    try {
                        Class<?> clazz = classLoader.loadClass(className);
                        InstanceScanner.hookClassConstructors(clazz);
                    } catch (Exception ignored) {}
                }

                List<Object> instances = InstanceScanner.getLiveInstances(className);
                totalCount += instances.size();
            }

            final int count = totalCount;
            mainHandler.post(() -> {
                statusText.setText("找到 " + count + " 个实例");
                showScanResults(classNames);
            });
        }).start();
    }

    private void showScanResults(String[] classNames) {
        resultContainer.removeAllViews();

        for (String className : classNames) {
            List<Object> instances = InstanceScanner.getLiveInstances(className);
            if (instances.isEmpty()) continue;

            // 类名标题
            TextView classTitle = new TextView(context);
            classTitle.setText(className + " (" + instances.size() + ")");
            classTitle.setTextSize(14);
            classTitle.setTextColor(COLOR_PRIMARY);
            classTitle.setTypeface(null, Typeface.BOLD);
            classTitle.setPadding(0, dp(16), 0, dp(8));
            resultContainer.addView(classTitle);

            for (Object instance : instances) {
                View item = createInstanceItem(instance);
                resultContainer.addView(item);
            }
        }

        if (resultContainer.getChildCount() == 0) {
            LinearLayout emptyCard = createCard();
            TextView emptyText = createInfoText("未找到任何实例");
            emptyCard.addView(emptyText);
            resultContainer.addView(emptyCard);
        }
    }

    private View createInstanceItem(Object instance) {
        LinearLayout card = createCard();
        card.setOrientation(LinearLayout.VERTICAL);

        // 类型名（可长按选择）
        TextView typeView = createSelectableText(instance.getClass().getSimpleName(), 15, COLOR_TEXT_PRIMARY);
        typeView.setTypeface(null, Typeface.BOLD);
        card.addView(typeView);

        // hashCode（可长按选择）
        TextView hashView = createSelectableText("@" + Integer.toHexString(instance.hashCode()), 12, COLOR_TEXT_SECONDARY);
        card.addView(hashView);

        // toString 预览
        String str = instance.toString();
        if (str.length() > 80) str = str.substring(0, 80) + "...";
        TextView toStringView = createSelectableText(str, 11, COLOR_TEXT_SECONDARY);
        toStringView.setPadding(0, dp(4), 0, 0);
        card.addView(toStringView);

        // 操作按钮
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(12), 0, 0);

        Button saveBtn = new Button(context);
        saveBtn.setText("保存");
        saveBtn.setOnClickListener(v -> {
            if (variableManager != null) {
                RetraceableVar var = new RetraceableVar(null, instance, RetraceableVar.VarSource.INSTANCE_SCAN);
                String varName = variableManager.addVariable(var);
                showToast("已保存为: " + varName);
            }
        });
        btnRow.addView(saveBtn);

        Button viewBtn = new Button(context);
        viewBtn.setText("查看详情");
        viewBtn.setOnClickListener(v -> {
            ClassViewPage page = new ClassViewPage(context, navigationStack, classLoader, instance, null);
            navigationStack.push(page);
        });
        btnRow.addView(viewBtn);

        card.addView(btnRow);

        return card;
    }

    private void showHookedClasses() {
        resultContainer.removeAllViews();

        java.util.Set<String> hookedClasses = InstanceScanner.getHookedClasses();

        if (hookedClasses.isEmpty()) {
            statusText.setText("尚未 Hook 任何类");

            LinearLayout emptyCard = createCard();
            TextView emptyText = createInfoText("尚未 Hook 任何类的构造函数");
            emptyCard.addView(emptyText);
            resultContainer.addView(emptyCard);
            return;
        }

        statusText.setText("已 Hook " + hookedClasses.size() + " 个类");

        for (String className : hookedClasses) {
            int count = InstanceScanner.getLiveInstances(className).size();

            LinearLayout card = createCard();
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView nameView = createSelectableText(className, 13, COLOR_TEXT_PRIMARY);
            nameView.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
            ));
            row.addView(nameView);

            TextView countView = new TextView(context);
            countView.setText("(" + count + ")");
            countView.setTextSize(13);
            countView.setTextColor(COLOR_TEXT_SECONDARY);
            row.addView(countView);

            card.addView(row);

            card.setOnClickListener(v -> {
                classNameInput.setText(className);
                scanInstances(className);
            });

            resultContainer.addView(card);
        }
    }
}
