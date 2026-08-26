package com.zitan.cdumpdex.reflection.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zitan.cdumpdex.ReflectUtils;
import com.zitan.cdumpdex.RetraceableVar;
import com.zitan.cdumpdex.reflection.VariableManager;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 方法调用页面
 */
public class MethodInvokePage extends BasePageView {

    private final ClassLoader classLoader;
    private final List<ClassLoader> additionalClassLoaders;
    private VariableManager variableManager;

    private String className;
    private Class<?> targetClass;
    private Method selectedMethod;
    private Object instance;
    private String instanceVarName;

    private LinearLayout classInputContainer;
    private LinearLayout instanceSelectContainer;
    private LinearLayout methodListContainer;
    private LinearLayout paramConfigContainer;
    private LinearLayout resultContainer;

    public MethodInvokePage(Context context, NavigationStack navigationStack, ClassLoader classLoader) {
        this(context, navigationStack, classLoader, null, null, null);
    }

    public MethodInvokePage(Context context, NavigationStack navigationStack, ClassLoader classLoader, List<ClassLoader> additionalClassLoaders) {
        this(context, navigationStack, classLoader, additionalClassLoaders, null, null);
    }

    public MethodInvokePage(Context context, NavigationStack navigationStack, ClassLoader classLoader, Method method, Object instance) {
        this(context, navigationStack, classLoader, null, method, instance);
    }

    public MethodInvokePage(Context context, NavigationStack navigationStack, ClassLoader classLoader, List<ClassLoader> additionalClassLoaders, Method method, Object instance) {
        super(context, navigationStack);
        this.classLoader = classLoader;
        this.additionalClassLoaders = additionalClassLoaders;
        try {
            this.variableManager = VariableManager.getInstance(context);
        } catch (Exception e) {
            android.util.Log.e("MethodInvokePage", "Failed to get VariableManager", e);
        }
        this.selectedMethod = method;
        this.instance = instance;

        if (method != null) {
            targetClass = method.getDeclaringClass();
            className = targetClass.getName();
        }
    }

    public void setInstanceVarName(String varName) {
        this.instanceVarName = varName;
    }

    @Override
    public String getTitle() {
        return selectedMethod != null ? selectedMethod.getName() : "调用方法";
    }

    @Override
    protected void initContent(LinearLayout contentLayout) {
        classInputContainer = new LinearLayout(context);
        classInputContainer.setOrientation(LinearLayout.VERTICAL);
        contentLayout.addView(classInputContainer);

        instanceSelectContainer = new LinearLayout(context);
        instanceSelectContainer.setOrientation(LinearLayout.VERTICAL);
        instanceSelectContainer.setVisibility(GONE);
        contentLayout.addView(instanceSelectContainer);

        methodListContainer = new LinearLayout(context);
        methodListContainer.setOrientation(LinearLayout.VERTICAL);
        methodListContainer.setVisibility(GONE);
        contentLayout.addView(methodListContainer);

        paramConfigContainer = new LinearLayout(context);
        paramConfigContainer.setOrientation(LinearLayout.VERTICAL);
        paramConfigContainer.setVisibility(GONE);
        contentLayout.addView(paramConfigContainer);

        resultContainer = new LinearLayout(context);
        resultContainer.setOrientation(LinearLayout.VERTICAL);
        resultContainer.setVisibility(GONE);
        contentLayout.addView(resultContainer);

        if (selectedMethod != null && instance != null) {
            showParamConfig();
        } else if (instance != null) {
            targetClass = instance.getClass();
            className = targetClass.getName();
            showMethodList();
        } else {
            showClassInput();
        }
    }

    private void showClassInput() {
        classInputContainer.removeAllViews();
        classInputContainer.setVisibility(VISIBLE);

        classInputContainer.addView(createSectionTitle("步骤1: 输入类名"));

        LinearLayout inputCard = createCard();
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);

        EditText input = new EditText(context);
        input.setHint("com.example.MyClass");
        input.setTextSize(14);
        input.setBackgroundResource(android.R.drawable.edit_text);
        input.setText(className != null ? className : "");
        input.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        ));
        input.setTag("class_input");
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showSoftInput(v);
        });
        input.setOnClickListener(v -> showSoftInput(v));
        inputRow.addView(input);

        Button loadBtn = new Button(context);
        loadBtn.setText("加载");
        loadBtn.setOnClickListener(v -> {
            className = input.getText().toString().trim();
            if (!className.isEmpty()) {
                hideSoftInput(input);
                loadClass();
            }
        });
        inputRow.addView(loadBtn);

        inputCard.addView(inputRow);
        classInputContainer.addView(inputCard);
    }

    private void loadClass() {
        new Thread(() -> {
            try {
                // 尝试从多个 ClassLoader 加载
                targetClass = ReflectUtils.loadClassFromMultipleLoaders(className, classLoader, additionalClassLoaders);

                if (selectedMethod == null) {
                    mainHandler.post(this::showMethodList);
                } else {
                    if (Modifier.isStatic(selectedMethod.getModifiers())) {
                        mainHandler.post(this::showParamConfig);
                    } else {
                        mainHandler.post(this::showInstanceSelect);
                    }
                }

            } catch (ClassNotFoundException e) {
                mainHandler.post(() -> showToast("找不到类: " + className + "\n尝试了所有可用的ClassLoader"));
            }
        }).start();
    }

    private void showInstanceSelect() {
        classInputContainer.setVisibility(GONE);
        methodListContainer.setVisibility(GONE);
        instanceSelectContainer.setVisibility(VISIBLE);
        instanceSelectContainer.removeAllViews();

        instanceSelectContainer.addView(createSectionTitle("步骤2: 选择实例"));

        List<String> matchingVars = new ArrayList<>();
        List<Object> matchingValues = new ArrayList<>();

        if (variableManager != null) {
            for (String varName : variableManager.getVariableNames()) {
                RetraceableVar var = variableManager.getVariable(varName);
                if (var != null && var.getValue() != null) {
                    if (targetClass.isInstance(var.getValue())) {
                        matchingVars.add(varName);
                        matchingValues.add(var.getValue());
                    }
                }
            }
        }

        if (matchingVars.isEmpty()) {
            LinearLayout emptyCard = createCard();
            TextView emptyText = createInfoText("没有找到匹配类型的实例\n请先在'保存的变量'中添加实例");
            emptyCard.addView(emptyText);
            instanceSelectContainer.addView(emptyCard);
            return;
        }

        for (int i = 0; i < matchingVars.size(); i++) {
            final int index = i;
            View item = createListItem(matchingVars.get(i), matchingValues.get(index).getClass().getSimpleName(), v -> {
                instance = matchingValues.get(index);
                instanceVarName = matchingVars.get(index);
                showParamConfig();
            });
            instanceSelectContainer.addView(item);
        }
    }

    private void showMethodList() {
        classInputContainer.setVisibility(GONE);
        instanceSelectContainer.setVisibility(GONE);
        methodListContainer.setVisibility(VISIBLE);
        methodListContainer.removeAllViews();

        methodListContainer.addView(createSectionTitle("选择方法"));

        List<Method> methods = ReflectUtils.getAllMethods(targetClass);

        List<Method> staticMethods = new ArrayList<>();
        List<Method> instanceMethods = new ArrayList<>();

        for (Method m : methods) {
            if (Modifier.isStatic(m.getModifiers())) {
                staticMethods.add(m);
            } else {
                instanceMethods.add(m);
            }
        }

        if (!staticMethods.isEmpty()) {
            TextView staticLabel = new TextView(context);
            staticLabel.setText("静态方法 (" + staticMethods.size() + ")");
            staticLabel.setTextSize(12);
            staticLabel.setTextColor(COLOR_TEXT_SECONDARY);
            staticLabel.setPadding(0, dp(8), 0, dp(4));
            methodListContainer.addView(staticLabel);

            for (Method m : staticMethods) {
                View item = createMethodItem(m);
                methodListContainer.addView(item);
            }
        }

        if (!instanceMethods.isEmpty()) {
            TextView instanceLabel = new TextView(context);
            instanceLabel.setText("实例方法 (" + instanceMethods.size() + ")");
            instanceLabel.setTextSize(12);
            instanceLabel.setTextColor(COLOR_TEXT_SECONDARY);
            instanceLabel.setPadding(0, dp(16), 0, dp(4));
            methodListContainer.addView(instanceLabel);

            for (Method m : instanceMethods) {
                View item = createMethodItem(m);
                methodListContainer.addView(item);
            }
        }
    }

    private View createMethodItem(Method method) {
        LinearLayout card = createCard();
        card.setOrientation(LinearLayout.VERTICAL);

        String signature = ReflectUtils.getMethodSignature(method);
        TextView sigView = createSelectableText(signature, 12, COLOR_TEXT_PRIMARY);
        card.addView(sigView);

        TextView returnType = createSelectableText("返回: " + method.getReturnType().getSimpleName(), 11, COLOR_TEXT_SECONDARY);
        returnType.setPadding(0, dp(4), 0, 0);
        card.addView(returnType);

        card.setOnClickListener(v -> {
            selectedMethod = method;
            if (Modifier.isStatic(method.getModifiers())) {
                showParamConfig();
            } else if (instance != null) {
                showParamConfig();
            } else {
                showInstanceSelect();
            }
        });

        return card;
    }

    private void showParamConfig() {
        classInputContainer.setVisibility(GONE);
        instanceSelectContainer.setVisibility(GONE);
        methodListContainer.setVisibility(GONE);
        paramConfigContainer.setVisibility(VISIBLE);
        paramConfigContainer.removeAllViews();

        // 方法信息卡片
        LinearLayout infoCard = createCard();

        TextView methodTitle = new TextView(context);
        methodTitle.setText("方法信息");
        methodTitle.setTextSize(14);
        methodTitle.setTextColor(COLOR_PRIMARY);
        methodTitle.setTypeface(null, Typeface.BOLD);
        infoCard.addView(methodTitle);

        infoCard.addView(createDivider());

        TextView methodView = createSelectableText(ReflectUtils.getMethodSignature(selectedMethod), 12, COLOR_TEXT_PRIMARY);
        infoCard.addView(methodView);

        if (!Modifier.isStatic(selectedMethod.getModifiers()) && instance != null) {
            TextView instView = createSelectableText(
                "实例: " + (instanceVarName != null ? instanceVarName : instance.getClass().getSimpleName()),
                12, COLOR_TEXT_SECONDARY
            );
            infoCard.addView(instView);
        }

        paramConfigContainer.addView(infoCard);

        Class<?>[] paramTypes = selectedMethod.getParameterTypes();

        if (paramTypes.length > 0) {
            paramConfigContainer.addView(createSectionTitle("配置参数"));

            for (int i = 0; i < paramTypes.length; i++) {
                final int index = i;
                Class<?> type = paramTypes[i];

                LinearLayout paramCard = createCard();

                TextView label = new TextView(context);
                label.setText("参数 " + (i + 1) + ": " + type.getSimpleName());
                label.setTextSize(14);
                label.setTextColor(COLOR_TEXT_PRIMARY);
                label.setTypeface(null, Typeface.BOLD);
                paramCard.addView(label);

                LinearLayout inputRow = new LinearLayout(context);
                inputRow.setOrientation(LinearLayout.HORIZONTAL);
                inputRow.setGravity(Gravity.CENTER_VERTICAL);
                inputRow.setPadding(0, dp(8), 0, 0);

                EditText input = new EditText(context);
                input.setHint("输入值");
                input.setTextSize(14);
                input.setBackgroundResource(android.R.drawable.edit_text);
                input.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
                ));
                input.setTag("param_" + i);
                input.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) showSoftInput(v);
                });
                input.setOnClickListener(v -> showSoftInput(v));
                inputRow.addView(input);

                Button varBtn = new Button(context);
                varBtn.setText("选变量");
                varBtn.setOnClickListener(v -> {
                    VariableListPage page = new VariableListPage(context, navigationStack, classLoader, true);
                    page.setOnVariableSelectedListener((varName, var) -> {
                        Object value = var.getValue();
                        if (value != null && type.isInstance(value)) {
                            input.setTag("param_value_" + index);
                            input.setText("已选: " + varName);
                            input.setTextColor(COLOR_PRIMARY);
                        } else {
                            showToast("类型不匹配");
                        }
                    });
                    navigationStack.push(page);
                });
                inputRow.addView(varBtn);

                paramCard.addView(inputRow);
                paramConfigContainer.addView(paramCard);
            }
        }

        // 调用按钮
        LinearLayout btnCard = createCard();
        Button callBtn = new Button(context);
        callBtn.setText("调用");
        callBtn.setBackgroundColor(COLOR_PRIMARY);
        callBtn.setTextColor(Color.WHITE);
        callBtn.setOnClickListener(v -> {
            hideSoftInput(paramConfigContainer);
            invokeMethod(paramTypes);
        });
        btnCard.addView(callBtn);
        paramConfigContainer.addView(btnCard);
    }

    private void invokeMethod(Class<?>[] paramTypes) {
        Object[] paramValues = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            EditText input = findViewWithTag("param_" + i);
            if (input == null) continue;

            String text = input.getText().toString().trim();
            if (text.startsWith("已选: ") && variableManager != null) {
                String varName = text.substring(4);
                RetraceableVar var = variableManager.getVariable(varName);
                if (var != null) {
                    paramValues[i] = var.getValue();
                }
            } else if (!text.isEmpty()) {
                paramValues[i] = parseValue(paramTypes[i], text);
            } else if (paramTypes[i].isPrimitive()) {
                paramValues[i] = getDefaultValue(paramTypes[i]);
            }
        }

        new Thread(() -> {
            try {
                Object result = ReflectUtils.invokeMethod(selectedMethod, instance, paramValues);
                mainHandler.post(() -> showResult(result));

            } catch (Exception e) {
                mainHandler.post(() -> {
                    resultContainer.removeAllViews();
                    resultContainer.setVisibility(VISIBLE);

                    LinearLayout errorCard = createCard();
                    TextView errorText = createSelectableText("调用失败: " + e.getMessage(), 14, 0xFFE53935);
                    errorCard.addView(errorText);
                    resultContainer.addView(errorCard);
                });
            }
        }).start();
    }

    private void showResult(Object result) {
        paramConfigContainer.setVisibility(GONE);
        resultContainer.setVisibility(VISIBLE);
        resultContainer.removeAllViews();

        // 结果卡片
        LinearLayout resultCard = createCard();

        TextView titleView = new TextView(context);
        titleView.setText("调用成功");
        titleView.setTextSize(18);
        titleView.setTextColor(COLOR_PRIMARY);
        titleView.setTypeface(null, Typeface.BOLD);
        resultCard.addView(titleView);

        resultCard.addView(createDivider());

        TextView typeView = createSelectableText("返回类型: " + selectedMethod.getReturnType().getSimpleName(), 13, COLOR_TEXT_PRIMARY);
        resultCard.addView(typeView);

        String valueStr = result != null ? ReflectUtils.formatValue(result) : "null";
        if (valueStr.length() > 200) valueStr = valueStr.substring(0, 200) + "...";

        TextView valueView = createSelectableText("值: " + valueStr, 12, COLOR_TEXT_SECONDARY);
        valueView.setPadding(0, dp(4), 0, 0);
        resultCard.addView(valueView);

        resultContainer.addView(resultCard);

        // 操作按钮
        LinearLayout btnCard = createCard();
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button copyBtn = new Button(context);
        copyBtn.setText("复制");
        copyBtn.setOnClickListener(v -> copyToClipboard("result", result != null ? result.toString() : "null"));
        btnRow.addView(copyBtn);

        if (result != null) {
            Button saveBtn = new Button(context);
            saveBtn.setText("保存");
            saveBtn.setOnClickListener(v -> {
                if (variableManager != null) {
                    RetraceableVar var = new RetraceableVar(null, result, RetraceableVar.VarSource.METHOD_RETURN);
                    var.setMethodInfo(targetClass.getName(), selectedMethod.getName(), instanceVarName);
                    String varName = variableManager.addVariable(var);
                    showToast("已保存为: " + varName);
                } else {
                    showToast("变量管理器未初始化");
                }
            });
            btnRow.addView(saveBtn);

            Button viewBtn = new Button(context);
            viewBtn.setText("查看");
            viewBtn.setOnClickListener(v -> {
                ClassViewPage page = new ClassViewPage(context, navigationStack, classLoader, result, null);
                navigationStack.push(page);
            });
            btnRow.addView(viewBtn);
        }

        btnCard.addView(btnRow);
        resultContainer.addView(btnCard);

        // 再次调用按钮
        Button againBtn = new Button(context);
        againBtn.setText("再次调用");
        againBtn.setOnClickListener(v -> {
            resultContainer.setVisibility(GONE);
            paramConfigContainer.setVisibility(VISIBLE);
        });
        resultContainer.addView(againBtn);
    }

    private Object parseValue(Class<?> type, String text) {
        try {
            if (type == String.class) return text;
            if (type == int.class || type == Integer.class) return Integer.parseInt(text);
            if (type == long.class || type == Long.class) return Long.parseLong(text);
            if (type == double.class || type == Double.class) return Double.parseDouble(text);
            if (type == float.class || type == Float.class) return Float.parseFloat(text);
            if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(text);
        } catch (Exception ignored) {}
        return null;
    }

    private Object getDefaultValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == boolean.class) return false;
        return null;
    }
}
