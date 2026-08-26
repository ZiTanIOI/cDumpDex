package com.zitan.cdumpdex.reflection.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
 * 变量详情页面
 * 显示变量的所有字段（包括值）和方法，点击方法可调用
 */
public class VariableDetailPage extends BasePageView {

    private final ClassLoader classLoader;
    private final Object instance;
    private final String varName;
    private VariableManager variableManager;
    private Class<?> targetClass;

    public VariableDetailPage(Context context, NavigationStack navigationStack, ClassLoader classLoader, Object instance, String varName) {
        super(context, navigationStack);
        this.classLoader = classLoader;
        this.instance = instance;
        this.varName = varName;
        try {
            this.variableManager = VariableManager.getInstance(context);
        } catch (Exception e) {
            android.util.Log.e("VariableDetailPage", "Failed to get VariableManager", e);
        }
        if (instance != null) {
            targetClass = instance.getClass();
        }
    }

    @Override
    public String getTitle() {
        return varName != null ? varName : "变量详情";
    }

    @Override
    protected void initContent(LinearLayout contentLayout) {
        if (instance == null) {
            LinearLayout errorCard = createCard();
            TextView errorText = createInfoText("变量值为 null");
            errorCard.addView(errorText);
            contentLayout.addView(errorCard);
            return;
        }

        // 基本信息
        LinearLayout infoCard = createCard();

        // 类型
        TextView typeLabel = new TextView(context);
        typeLabel.setText("类型");
        typeLabel.setTextSize(12);
        typeLabel.setTextColor(COLOR_TEXT_SECONDARY);
        infoCard.addView(typeLabel);

        TextView typeView = createSelectableText(targetClass.getName(), 14, COLOR_TEXT_PRIMARY);
        typeView.setTypeface(null, Typeface.BOLD);
        infoCard.addView(typeView);

        infoCard.addView(createDivider());

        // hashCode
        TextView hashLabel = new TextView(context);
        hashLabel.setText("hashCode");
        hashLabel.setTextSize(12);
        hashLabel.setTextColor(COLOR_TEXT_SECONDARY);
        infoCard.addView(hashLabel);

        TextView hashView = createSelectableText(Integer.toHexString(instance.hashCode()), 14, COLOR_TEXT_PRIMARY);
        infoCard.addView(hashView);

        infoCard.addView(createDivider());

        // toString
        TextView toStringLabel = new TextView(context);
        toStringLabel.setText("toString()");
        toStringLabel.setTextSize(12);
        toStringLabel.setTextColor(COLOR_TEXT_SECONDARY);
        infoCard.addView(toStringLabel);

        String str = instance.toString();
        if (str.length() > 300) str = str.substring(0, 300) + "...";
        TextView toStringView = createSelectableText(str, 12, COLOR_TEXT_SECONDARY);
        infoCard.addView(toStringView);

        contentLayout.addView(infoCard);

        // 操作按钮
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

        // 字段列表
        contentLayout.addView(createSectionTitle("字段"));
        showFields(contentLayout);

        // 方法列表
        contentLayout.addView(createSectionTitle("方法"));
        showMethods(contentLayout);
    }

    private void showFields(LinearLayout contentLayout) {
        List<Field> fields = ReflectUtils.getAllFields(targetClass);

        if (fields.isEmpty()) {
            LinearLayout emptyCard = createCard();
            TextView emptyText = createInfoText("无字段");
            emptyCard.addView(emptyText);
            contentLayout.addView(emptyCard);
            return;
        }

        for (Field field : fields) {
            View item = createFieldItem(field);
            contentLayout.addView(item);
        }
    }

    private View createFieldItem(Field field) {
        LinearLayout card = createCard();
        card.setOrientation(LinearLayout.VERTICAL);

        // 字段签名
        String signature = ReflectUtils.getFieldSignature(field);
        TextView sigView = createSelectableText(signature, 13, COLOR_TEXT_PRIMARY);
        sigView.setTypeface(null, Typeface.BOLD);
        card.addView(sigView);

        // 值
        try {
            Object value = ReflectUtils.getFieldValue(field, instance);
            String valueStr = ReflectUtils.formatValue(value);

            TextView valueLabel = new TextView(context);
            valueLabel.setText("值:");
            valueLabel.setTextSize(11);
            valueLabel.setTextColor(COLOR_TEXT_SECONDARY);
            valueLabel.setPadding(0, dp(4), 0, 0);
            card.addView(valueLabel);

            TextView valueView = createSelectableText(valueStr, 12, COLOR_TEXT_SECONDARY);
            card.addView(valueView);

            // 点击保存
            if (value != null && variableManager != null) {
                card.setBackgroundResource(getSelectableBackground());
                card.setOnClickListener(v -> {
                    RetraceableVar var = new RetraceableVar(null, value,
                        Modifier.isStatic(field.getModifiers()) ?
                            RetraceableVar.VarSource.STATIC_FIELD : RetraceableVar.VarSource.FIELD_ACCESS);
                    var.setFieldInfo(targetClass.getName(), field.getName(), varName);
                    String savedName = variableManager.addVariable(var);
                    showToast("已保存为: " + savedName);
                });
            }

        } catch (Exception e) {
            TextView errorView = createSelectableText("无法访问: " + e.getMessage(), 11, 0xFFE53935);
            errorView.setPadding(0, dp(4), 0, 0);
            card.addView(errorView);
        }

        return card;
    }

    private void showMethods(LinearLayout contentLayout) {
        List<Method> methods = ReflectUtils.getAllMethods(targetClass);

        if (methods.isEmpty()) {
            LinearLayout emptyCard = createCard();
            TextView emptyText = createInfoText("无方法");
            emptyCard.addView(emptyText);
            contentLayout.addView(emptyCard);
            return;
        }

        // 分组：静态方法 / 实例方法
        boolean hasStatic = false;
        boolean hasInstance = false;

        for (Method m : methods) {
            if (Modifier.isStatic(m.getModifiers())) {
                hasStatic = true;
            } else {
                hasInstance = true;
            }
        }

        if (hasStatic) {
            TextView staticLabel = new TextView(context);
            staticLabel.setText("静态方法");
            staticLabel.setTextSize(12);
            staticLabel.setTextColor(COLOR_TEXT_SECONDARY);
            staticLabel.setPadding(0, dp(8), 0, dp(4));
            contentLayout.addView(staticLabel);

            for (Method m : methods) {
                if (Modifier.isStatic(m.getModifiers())) {
                    View item = createMethodItem(m);
                    contentLayout.addView(item);
                }
            }
        }

        if (hasInstance) {
            TextView instanceLabel = new TextView(context);
            instanceLabel.setText("实例方法");
            instanceLabel.setTextSize(12);
            instanceLabel.setTextColor(COLOR_TEXT_SECONDARY);
            instanceLabel.setPadding(0, dp(16), 0, dp(4));
            contentLayout.addView(instanceLabel);

            for (Method m : methods) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    View item = createMethodItem(m);
                    contentLayout.addView(item);
                }
            }
        }
    }

    private View createMethodItem(Method method) {
        LinearLayout card = createCard();
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(getSelectableBackground());

        // 方法签名
        String signature = ReflectUtils.getMethodSignature(method);
        TextView sigView = createSelectableText(signature, 12, COLOR_TEXT_PRIMARY);
        sigView.setTypeface(null, Typeface.BOLD);
        card.addView(sigView);

        // 返回类型
        TextView returnView = createSelectableText("返回: " + method.getReturnType().getSimpleName(), 11, COLOR_TEXT_SECONDARY);
        returnView.setPadding(0, dp(2), 0, 0);
        card.addView(returnView);

        // 点击调用
        card.setOnClickListener(v -> {
            MethodInvokePage page = new MethodInvokePage(context, navigationStack, classLoader, method, instance);
            page.setInstanceVarName(varName);
            navigationStack.push(page);
        });

        return card;
    }
}
