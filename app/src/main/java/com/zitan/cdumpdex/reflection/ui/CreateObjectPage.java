package com.zitan.cdumpdex.reflection.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zitan.cdumpdex.ReflectUtils;
import com.zitan.cdumpdex.RetraceableVar;
import com.zitan.cdumpdex.reflection.VariableManager;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 创建对象页面
 */
public class CreateObjectPage extends BasePageView {

    private final ClassLoader classLoader;
    private VariableManager variableManager;

    private String className;
    private Class<?> targetClass;
    private Constructor<?> selectedConstructor;
    private Class<?>[] paramTypes;
    private Object[] paramValues;
    private String[] paramVarRefs;

    private EditText classNameInput;
    private LinearLayout constructorListContainer;
    private LinearLayout paramConfigContainer;
    private LinearLayout resultContainer;

    public CreateObjectPage(Context context, NavigationStack navigationStack, ClassLoader classLoader) {
        super(context, navigationStack);
        this.classLoader = classLoader;
        try {
            this.variableManager = VariableManager.getInstance(context);
        } catch (Exception e) {
            android.util.Log.e("CreateObjectPage", "Failed to get VariableManager", e);
        }
    }

    @Override
    public String getTitle() {
        return "创建对象";
    }

    @Override
    protected void initContent(LinearLayout contentLayout) {
        // 步骤1: 输入类名
        contentLayout.addView(createSectionTitle("步骤1: 输入类名"));

        LinearLayout inputCard = createCard();
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        classNameInput = new EditText(context);
        classNameInput.setHint("com.example.MyClass");
        classNameInput.setTextSize(14);
        classNameInput.setTextColor(COLOR_TEXT_PRIMARY);
        classNameInput.setSingleLine(true);
        classNameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        classNameInput.setBackgroundResource(android.R.drawable.edit_text);
        classNameInput.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        ));
        classNameInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showSoftInput(v);
        });
        classNameInput.setOnClickListener(v -> showSoftInput(v));
        inputRow.addView(classNameInput);

        Button loadBtn = new Button(context);
        loadBtn.setText("加载");
        loadBtn.setOnClickListener(v -> loadClass());
        inputRow.addView(loadBtn);

        inputCard.addView(inputRow);
        contentLayout.addView(inputCard);

        // 步骤2: 构造函数列表容器
        constructorListContainer = new LinearLayout(context);
        constructorListContainer.setOrientation(LinearLayout.VERTICAL);
        contentLayout.addView(constructorListContainer);

        // 步骤3: 参数配置容器
        paramConfigContainer = new LinearLayout(context);
        paramConfigContainer.setOrientation(LinearLayout.VERTICAL);
        paramConfigContainer.setVisibility(GONE);
        contentLayout.addView(paramConfigContainer);

        // 结果容器
        resultContainer = new LinearLayout(context);
        resultContainer.setOrientation(LinearLayout.VERTICAL);
        resultContainer.setVisibility(GONE);
        contentLayout.addView(resultContainer);
    }

    private void loadClass() {
        className = classNameInput.getText().toString().trim();
        if (className.isEmpty()) {
            showToast("请输入类名");
            return;
        }

        // 隐藏输入法
        hideSoftInput(classNameInput);

        constructorListContainer.removeAllViews();
        constructorListContainer.addView(createInfoText("正在加载..."));

        new Thread(() -> {
            try {
                targetClass = classLoader.loadClass(className);
                mainHandler.post(this::showConstructors);
            } catch (ClassNotFoundException e) {
                mainHandler.post(() -> {
                    constructorListContainer.removeAllViews();
                    constructorListContainer.addView(createInfoText("找不到类: " + className));
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    constructorListContainer.removeAllViews();
                    constructorListContainer.addView(createInfoText("加载失败: " + e.getMessage()));
                });
            }
        }).start();
    }

    private void showConstructors() {
        constructorListContainer.removeAllViews();
        constructorListContainer.addView(createSectionTitle("步骤2: 选择构造函数"));

        List<Constructor<?>> constructors = ReflectUtils.getAllConstructors(targetClass);

        if (constructors.isEmpty()) {
            constructorListContainer.addView(createInfoText("没有可访问的构造函数"));
            return;
        }

        // 检查无参构造函数，添加快速创建按钮
        for (Constructor<?> c : constructors) {
            if (c.getParameterCount() == 0) {
                LinearLayout quickCard = createCard();
                Button quickBtn = new Button(context);
                quickBtn.setText("快速创建（无参构造）");
                quickBtn.setBackgroundColor(COLOR_PRIMARY);
                quickBtn.setTextColor(Color.WHITE);
                quickBtn.setOnClickListener(v -> {
                    selectedConstructor = c;
                    paramTypes = new Class<?>[0];
                    paramValues = new Object[0];
                    createObject();
                });
                quickCard.addView(quickBtn);
                constructorListContainer.addView(quickCard);
                break;
            }
        }

        // 构造函数列表
        for (Constructor<?> c : constructors) {
            String signature = ReflectUtils.getConstructorSignature(c);
            View item = createListItem(signature, "参数: " + c.getParameterCount() + " 个", v -> {
                selectedConstructor = c;
                paramTypes = c.getParameterTypes();
                paramVarRefs = new String[paramTypes.length];
                paramValues = new Object[paramTypes.length];
                showParamConfig();
            });
            constructorListContainer.addView(item);
        }
    }

    private void showParamConfig() {
        constructorListContainer.setVisibility(GONE);
        paramConfigContainer.setVisibility(VISIBLE);
        paramConfigContainer.removeAllViews();

        // 方法信息卡片
        LinearLayout infoCard = createCard();
        TextView methodInfo = createSelectableText(
            "构造函数: " + selectedConstructor.getDeclaringClass().getSimpleName(),
            14, COLOR_TEXT_PRIMARY
        );
        infoCard.addView(methodInfo);
        paramConfigContainer.addView(infoCard);

        if (paramTypes.length == 0) {
            createObject();
            return;
        }

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

            // 输入行
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
            input.setTag("param_input_" + i);
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
                        paramValues[index] = value;
                        paramVarRefs[index] = varName;
                        input.setText("已选: " + varName);
                        input.setTextColor(COLOR_PRIMARY);
                    } else {
                        showToast("类型不匹配，需要: " + type.getSimpleName());
                    }
                });
                navigationStack.push(page);
            });
            inputRow.addView(varBtn);

            paramCard.addView(inputRow);
            paramConfigContainer.addView(paramCard);
        }

        // 创建按钮
        LinearLayout btnCard = createCard();
        Button createBtn = new Button(context);
        createBtn.setText("创建对象");
        createBtn.setBackgroundColor(COLOR_PRIMARY);
        createBtn.setTextColor(Color.WHITE);
        createBtn.setOnClickListener(v -> {
            if (collectParamValues()) {
                hideSoftInput(paramConfigContainer);
                createObject();
            }
        });
        btnCard.addView(createBtn);
        paramConfigContainer.addView(btnCard);
    }

    private boolean collectParamValues() {
        for (int i = 0; i < paramTypes.length; i++) {
            if (paramVarRefs[i] != null) continue;

            EditText input = findViewWithTag("param_input_" + i);
            if (input == null) continue;

            String text = input.getText().toString().trim();
            if (text.startsWith("已选: ")) continue;

            if (text.isEmpty() && paramTypes[i].isPrimitive()) {
                paramValues[i] = getDefaultValue(paramTypes[i]);
            } else if (!text.isEmpty()) {
                if (!parseParamValue(i, paramTypes[i], text)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean parseParamValue(int index, Class<?> type, String input) {
        try {
            if (type == String.class) {
                paramValues[index] = input;
            } else if (type == int.class || type == Integer.class) {
                paramValues[index] = Integer.parseInt(input);
            } else if (type == long.class || type == Long.class) {
                paramValues[index] = Long.parseLong(input);
            } else if (type == double.class || type == Double.class) {
                paramValues[index] = Double.parseDouble(input);
            } else if (type == float.class || type == Float.class) {
                paramValues[index] = Float.parseFloat(input);
            } else if (type == boolean.class || type == Boolean.class) {
                paramValues[index] = Boolean.parseBoolean(input);
            } else {
                paramValues[index] = null;
            }
            return true;
        } catch (Exception e) {
            showToast("参数 " + (index + 1) + " 解析失败: " + e.getMessage());
            return false;
        }
    }

    private Object getDefaultValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return '\0';
        return null;
    }

    private void createObject() {
        new Thread(() -> {
            try {
                Object instance = ReflectUtils.newInstance(selectedConstructor, paramValues);

                LinkedHashMap<Integer, String> paramRefs = null;
                LinkedHashMap<Integer, Object> primParams = null;

                if (paramVarRefs != null && paramVarRefs.length > 0) {
                    paramRefs = new LinkedHashMap<>();
                    primParams = new LinkedHashMap<>();
                    for (int i = 0; i < paramVarRefs.length; i++) {
                        if (paramVarRefs[i] != null) {
                            paramRefs.put(i, paramVarRefs[i]);
                        } else if (paramValues[i] != null) {
                            primParams.put(i, paramValues[i]);
                        }
                    }
                }

                RetraceableVar var = new RetraceableVar(null, instance, RetraceableVar.VarSource.CONSTRUCTOR);
                var.setConstructorParams(paramRefs, primParams);

                final String varName;
                if (variableManager != null) {
                    varName = variableManager.addVariable(var);
                } else {
                    varName = "obj_" + Integer.toHexString(instance.hashCode());
                }

                mainHandler.post(() -> showResult(varName, instance));

            } catch (Exception e) {
                mainHandler.post(() -> {
                    paramConfigContainer.setVisibility(GONE);
                    resultContainer.setVisibility(VISIBLE);
                    resultContainer.removeAllViews();

                    LinearLayout errorCard = createCard();
                    TextView errorText = createSelectableText("创建失败: " + e.getMessage(), 14, 0xFFE53935);
                    errorCard.addView(errorText);
                    resultContainer.addView(errorCard);
                });
            }
        }).start();
    }

    private void showResult(String varName, Object instance) {
        paramConfigContainer.setVisibility(GONE);
        constructorListContainer.setVisibility(GONE);
        resultContainer.setVisibility(VISIBLE);
        resultContainer.removeAllViews();

        // 成功卡片
        LinearLayout successCard = createCard();

        TextView titleView = new TextView(context);
        titleView.setText("创建成功");
        titleView.setTextSize(18);
        titleView.setTextColor(COLOR_PRIMARY);
        titleView.setTypeface(null, Typeface.BOLD);
        successCard.addView(titleView);

        successCard.addView(createDivider());

        TextView nameView = createSelectableText("变量名: " + varName, 15, COLOR_TEXT_PRIMARY);
        successCard.addView(nameView);

        TextView typeView = createSelectableText("类型: " + instance.getClass().getName(), 13, COLOR_TEXT_SECONDARY);
        successCard.addView(typeView);

        String str = instance.toString();
        if (str.length() > 200) str = str.substring(0, 200) + "...";

        TextView valueView = createSelectableText("toString: " + str, 12, COLOR_TEXT_SECONDARY);
        successCard.addView(valueView);

        resultContainer.addView(successCard);

        // 操作按钮
        LinearLayout btnCard = createCard();
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button viewBtn = new Button(context);
        viewBtn.setText("查看详情");
        viewBtn.setOnClickListener(v -> {
            ClassViewPage page = new ClassViewPage(context, navigationStack, classLoader, instance, varName);
            navigationStack.push(page);
        });
        btnRow.addView(viewBtn);

        Button newBtn = new Button(context);
        newBtn.setText("继续创建");
        newBtn.setOnClickListener(v -> {
            resultContainer.setVisibility(GONE);
            constructorListContainer.setVisibility(VISIBLE);
        });
        btnRow.addView(newBtn);

        btnCard.addView(btnRow);
        resultContainer.addView(btnCard);
    }
}
