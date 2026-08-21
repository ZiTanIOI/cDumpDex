package com.zitan.cdumpdex.reflection.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zitan.cdumpdex.ReflectUtils;
import com.zitan.cdumpdex.RetraceableVar;
import com.zitan.cdumpdex.reflection.VariableManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * 类结构查看页面
 */
public class ClassViewPage extends BasePageView {

    private final ClassLoader classLoader;
    private final List<ClassLoader> additionalClassLoaders;
    private final Object instance;
    private final String varName;
    private VariableManager variableManager;

    private Class<?> targetClass;

    public ClassViewPage(Context context, NavigationStack navigationStack, ClassLoader classLoader, Object instance, String varName) {
        this(context, navigationStack, classLoader, null, instance, varName);
    }

    public ClassViewPage(Context context, NavigationStack navigationStack, ClassLoader classLoader, List<ClassLoader> additionalClassLoaders, Object instance, String varName) {
        super(context, navigationStack);
        this.classLoader = classLoader;
        this.additionalClassLoaders = additionalClassLoaders;
        this.instance = instance;
        this.varName = varName;
        try {
            this.variableManager = VariableManager.getInstance(context);
        } catch (Exception e) {
            android.util.Log.e("ClassViewPage", "Failed to get VariableManager", e);
        }

        if (instance != null) {
            targetClass = instance.getClass();
        }
    }

    /**
     * 只传入 Class 的构造函数（无实例）
     */
    public ClassViewPage(Context context, NavigationStack navigationStack, ClassLoader classLoader, Class<?> targetClass) {
        this(context, navigationStack, classLoader, null, targetClass);
    }

    public ClassViewPage(Context context, NavigationStack navigationStack, ClassLoader classLoader, List<ClassLoader> additionalClassLoaders, Class<?> targetClass) {
        super(context, navigationStack);
        this.classLoader = classLoader;
        this.additionalClassLoaders = additionalClassLoaders;
        this.instance = null;
        this.varName = null;
        this.targetClass = targetClass;
        try {
            this.variableManager = VariableManager.getInstance(context);
        } catch (Exception e) {
            android.util.Log.e("ClassViewPage", "Failed to get VariableManager", e);
        }
    }

    @Override
    public String getTitle() {
        if (targetClass != null) {
            return targetClass.getSimpleName();
        }
        return "类结构";
    }

    @Override
    protected void initContent(LinearLayout contentLayout) {
        if (instance == null && targetClass == null) {
            showClassNameInput(contentLayout);
        } else {
            showClassStructure(contentLayout);
        }
    }

    private void showClassNameInput(LinearLayout contentLayout) {
        contentLayout.addView(createSectionTitle("输入要查看的类名"));

        LinearLayout inputCard = createCard();
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);

        EditText input = new EditText(context);
        input.setHint("com.example.MyClass");
        input.setTextSize(14);
        input.setBackgroundResource(android.R.drawable.edit_text);
        input.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        ));
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showSoftInput(v);
        });
        input.setOnClickListener(v -> showSoftInput(v));
        inputRow.addView(input);

        Button viewBtn = new Button(context);
        viewBtn.setText("查看");
        viewBtn.setOnClickListener(v -> {
            String className = input.getText().toString().trim();
            if (!className.isEmpty()) {
                loadClass(className);
            }
        });
        inputRow.addView(viewBtn);

        inputCard.addView(inputRow);
        contentLayout.addView(inputCard);
    }

    private void loadClass(String className) {
        new Thread(() -> {
            try {
                // 尝试从多个 ClassLoader 加载
                targetClass = ReflectUtils.loadClassFromMultipleLoaders(className, classLoader, additionalClassLoaders);
                mainHandler.post(() -> {
                    contentLayout.removeAllViews();
                    showClassStructure(contentLayout);
                });
            } catch (ClassNotFoundException e) {
                mainHandler.post(() -> showToast("找不到类: " + className + "\n尝试了所有可用的ClassLoader"));
            }
        }).start();
    }

    private void showClassStructure(LinearLayout contentLayout) {
        // 基本信息卡片
        LinearLayout infoCard = createCard();

        if (instance != null && varName != null) {
            TextView nameView = createSelectableText("变量名: " + varName, 14, COLOR_TEXT_PRIMARY);
            nameView.setTypeface(null, Typeface.BOLD);
            infoCard.addView(nameView);
        }

        TextView classView = createSelectableText("类: " + targetClass.getName(), 13, COLOR_TEXT_SECONDARY);
        infoCard.addView(classView);

        if (instance != null) {
            TextView hashView = createSelectableText("hashCode: " + Integer.toHexString(instance.hashCode()), 12, COLOR_TEXT_SECONDARY);
            infoCard.addView(hashView);
        }

        contentLayout.addView(infoCard);

        // 继承链
        contentLayout.addView(createSectionTitle("继承链"));
        contentLayout.addView(createExpandableItem("查看继承链", v -> showInheritanceChain()));

        // 接口
        contentLayout.addView(createSectionTitle("接口"));
        contentLayout.addView(createExpandableItem("查看实现的接口", v -> showInterfaces()));

        // 字段
        contentLayout.addView(createSectionTitle("字段"));
        contentLayout.addView(createExpandableItem("查看所有字段", v -> showFields()));

        // 方法
        contentLayout.addView(createSectionTitle("方法"));
        contentLayout.addView(createExpandableItem("查看所有方法", v -> showMethods()));

        // 操作按钮
        if (instance != null) {
            contentLayout.addView(createSectionTitle("操作"));

            LinearLayout btnCard = createCard();
            LinearLayout btnRow = new LinearLayout(context);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.CENTER);

            Button copyBtn = new Button(context);
            copyBtn.setText("复制toString");
            copyBtn.setOnClickListener(v -> copyToClipboard("toString", instance.toString()));
            btnRow.addView(copyBtn);

            if (varName != null) {
                Button deleteBtn = new Button(context);
                deleteBtn.setText("删除变量");
                deleteBtn.setTextColor(0xFFE53935);
                deleteBtn.setOnClickListener(v -> {
                    if (variableManager != null) {
                        variableManager.removeVariable(varName);
                        showToast("已删除: " + varName);
                        navigationStack.goBack();
                    }
                });
                btnRow.addView(deleteBtn);
            }

            btnCard.addView(btnRow);
            contentLayout.addView(btnCard);
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

    private void showInheritanceChain() {
        List<Class<?>> chain = ReflectUtils.getInheritanceChain(targetClass);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(16), dp(16), dp(16));

        for (int i = chain.size() - 1; i >= 0; i--) {
            StringBuilder indent = new StringBuilder();
            for (int j = i; j < chain.size() - 1; j++) {
                indent.append("    ");
            }

            TextView tv = createSelectableText(indent + "└─ " + chain.get(i).getName(), 13, COLOR_TEXT_PRIMARY);
            tv.setPadding(0, dp(4), 0, dp(4));
            container.addView(tv);
        }

        showViewDialog("继承链", container);
    }

    private void showInterfaces() {
        List<Class<?>> interfaces = ReflectUtils.getImplementedInterfaces(targetClass);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(16), dp(16), dp(16));

        if (interfaces.isEmpty()) {
            TextView emptyText = createInfoText("无实现的接口");
            container.addView(emptyText);
        } else {
            for (Class<?> iface : interfaces) {
                TextView tv = createSelectableText(iface.getName(), 13, COLOR_TEXT_PRIMARY);
                tv.setPadding(0, dp(4), 0, dp(4));
                container.addView(tv);
            }
        }

        showViewDialog("实现的接口", container);
    }

    private void showFields() {
        List<Field> fields = ReflectUtils.getAllFields(targetClass);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        if (fields.isEmpty()) {
            container.setPadding(dp(16), dp(16), dp(16), dp(16));
            TextView emptyText = createInfoText("无字段");
            container.addView(emptyText);
        } else {
            for (Field field : fields) {
                View item = createFieldItem(field);
                container.addView(item);
            }
        }

        showViewDialog("字段 (" + fields.size() + ")", container);
    }

    private View createFieldItem(Field field) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setBackgroundColor(COLOR_CARD);
        item.setPadding(dp(12), dp(10), dp(12), dp(10));

        // 字段签名（可长按选择）
        String signature = ReflectUtils.getFieldSignature(field);
        TextView sigView = createSelectableText(signature, 12, COLOR_TEXT_PRIMARY);
        item.addView(sigView);

        // 值（如果有实例或是静态字段）
        if (instance != null || Modifier.isStatic(field.getModifiers())) {
            try {
                Object value = ReflectUtils.getFieldValue(field, instance);
                String valueStr = ReflectUtils.formatValue(value);
                TextView valueView = createSelectableText("= " + valueStr, 11, COLOR_TEXT_SECONDARY);
                valueView.setPadding(0, dp(2), 0, 0);
                item.addView(valueView);

                // 点击保存
                item.setOnClickListener(v -> {
                    if (value != null && variableManager != null) {
                        RetraceableVar var = new RetraceableVar(null, value,
                            Modifier.isStatic(field.getModifiers()) ?
                                RetraceableVar.VarSource.STATIC_FIELD : RetraceableVar.VarSource.FIELD_ACCESS);
                        var.setFieldInfo(targetClass.getName(), field.getName(), varName);
                        String savedName = variableManager.addVariable(var);
                        showToast("已保存为: " + savedName);
                    }
                });

            } catch (Exception e) {
                TextView errorView = createSelectableText("无法访问: " + e.getMessage(), 11, 0xFFE53935);
                errorView.setPadding(0, dp(2), 0, 0);
                item.addView(errorView);
            }
        }

        // 分隔线
        View divider = new View(context);
        divider.setBackgroundColor(COLOR_DIVIDER);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1
        ));
        item.addView(divider);

        return item;
    }

    private void showMethods() {
        List<Method> methods = ReflectUtils.getAllMethods(targetClass);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        if (methods.isEmpty()) {
            container.setPadding(dp(16), dp(16), dp(16), dp(16));
            TextView emptyText = createInfoText("无方法");
            container.addView(emptyText);
        } else {
            for (Method method : methods) {
                View item = createMethodItem(method);
                container.addView(item);
            }
        }

        showViewDialog("方法 (" + methods.size() + ")", container);
    }

    private View createMethodItem(Method method) {
        LinearLayout card = createCard();
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(getSelectableBackground());

        // 方法签名
        String signature = ReflectUtils.getMethodSignature(method);
        TextView sigView = createSelectableText(signature, 13, COLOR_TEXT_PRIMARY);
        sigView.setTypeface(null, Typeface.BOLD);
        card.addView(sigView);

        // 返回类型
        TextView returnView = createSelectableText("返回: " + method.getReturnType().getSimpleName(), 11, COLOR_TEXT_SECONDARY);
        returnView.setPadding(0, dp(2), 0, 0);
        card.addView(returnView);

        // 参数类型
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length > 0) {
            StringBuilder params = new StringBuilder("参数: ");
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) params.append(", ");
                params.append(paramTypes[i].getSimpleName());
            }
            TextView paramView = createSelectableText(params.toString(), 11, COLOR_TEXT_SECONDARY);
            paramView.setPadding(0, dp(2), 0, 0);
            card.addView(paramView);
        }

        // 点击整个卡片调用方法
        card.setOnClickListener(v -> {
            MethodInvokePage page = new MethodInvokePage(context, navigationStack, classLoader, method, instance);
            page.setInstanceVarName(varName);
            navigationStack.push(page);
        });

        return card;
    }

    private void showViewDialog(String title, View content) {
        android.widget.ScrollView scrollView = new android.widget.ScrollView(context);
        scrollView.addView(content);

        new android.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scrollView)
            .setNegativeButton("关闭", null)
            .create().show();
    }
}
