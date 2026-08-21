package com.zitan.cdumpdex.reflection;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.zitan.cdumpdex.ReflectUtils;
import com.zitan.cdumpdex.RetraceableVar;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 创建对象对话框
 * 支持选择构造函数、配置参数、创建对象并保存到变量
 */
public class CreateObjectDialog {

    private final Context context;
    private final ClassLoader classLoader;
    private final VariableManager variableManager;

    // 当前状态
    private String selectedClassName;
    private Constructor<?> selectedConstructor;
    private Class<?>[] paramTypes;
    private Object[] paramValues;
    private String[] paramVarRefs;  // 参数变量引用

    public CreateObjectDialog(Context context, ClassLoader classLoader) {
        this.context = context;
        this.classLoader = classLoader;
        this.variableManager = VariableManager.getInstance(context);
    }

    /**
     * 显示创建对象对话框
     */
    public void show() {
        show(null);
    }

    /**
     * 显示创建对象对话框，预填类名
     */
    public void show(String presetClassName) {
        // 步骤1: 输入类名
        showClassNameInputDialog(presetClassName);
    }

    /**
     * 步骤1: 输入类名对话框
     */
    private void showClassNameInputDialog(String presetClassName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("创建对象 - 输入类名");

        final EditText input = new EditText(context);
        input.setHint("com.example.MyClass");
        if (presetClassName != null) {
            input.setText(presetClassName);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(50, 20, 50, 20);
        input.setLayoutParams(lp);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView hint = new TextView(context);
        hint.setText("输入完整的类名（包括包名）");
        hint.setPadding(50, 20, 50, 10);
        container.addView(hint);
        container.addView(input);

        builder.setView(container);

        builder.setPositiveButton("下一步", (dialog, which) -> {
            String className = input.getText().toString().trim();
            if (className.isEmpty()) {
                Toast.makeText(context, "请输入类名", Toast.LENGTH_SHORT).show();
                return;
            }
            loadClassAndShowConstructors(className);
        });

        builder.setNegativeButton("取消", null);
        builder.create().show();
    }

    /**
     * 加载类并显示构造函数列表
     */
    private void loadClassAndShowConstructors(String className) {
        try {
            Class<?> clazz = classLoader.loadClass(className);
            selectedClassName = className;
            showConstructorListDialog(clazz);
        } catch (ClassNotFoundException e) {
            Toast.makeText(context, "找不到类: " + className, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context, "加载类失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 步骤2: 显示构造函数列表
     */
    private void showConstructorListDialog(Class<?> clazz) {
        List<Constructor<?>> constructors = ReflectUtils.getAllConstructors(clazz);

        if (constructors.isEmpty()) {
            Toast.makeText(context, "该类没有可访问的构造函数", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查是否有无参构造函数
        final boolean hasNoArg;
        {
            boolean found = false;
            for (Constructor<?> c : constructors) {
                if (c.getParameterCount() == 0) {
                    found = true;
                    break;
                }
            }
            hasNoArg = found;
        }

        List<String> items = new ArrayList<>();
        for (Constructor<?> c : constructors) {
            items.add(ReflectUtils.getConstructorSignature(c));
        }

        // 添加快捷选项
        if (hasNoArg) {
            items.add(0, "⚡ 快速创建（无参构造函数）");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("选择构造函数 (" + constructors.size() + ")");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
            android.R.layout.simple_list_item_1, items);

        builder.setAdapter(adapter, (dialog, which) -> {
            if (hasNoArg && which == 0) {
                // 快速创建
                for (Constructor<?> c : constructors) {
                    if (c.getParameterCount() == 0) {
                        selectedConstructor = c;
                        paramTypes = new Class<?>[0];
                        paramValues = new Object[0];
                        createObject();
                        return;
                    }
                }
            } else {
                int index = hasNoArg ? which - 1 : which;
                if (index >= 0 && index < constructors.size()) {
                    selectedConstructor = constructors.get(index);
                    paramTypes = selectedConstructor.getParameterTypes();
                    paramVarRefs = new String[paramTypes.length];
                    paramValues = new Object[paramTypes.length];
                    showParameterConfigDialog();
                }
            }
        });

        builder.setNegativeButton("返回", (dialog, which) -> {
            showClassNameInputDialog(selectedClassName);
        });

        builder.create().show();
    }

    /**
     * 步骤3: 配置参数对话框
     */
    private void showParameterConfigDialog() {
        if (paramTypes.length == 0) {
            // 无参数，直接创建
            createObject();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("配置参数");

        ScrollView scrollView = new ScrollView(context);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(30, 30, 30, 30);

        // 为每个参数创建配置项
        for (int i = 0; i < paramTypes.length; i++) {
            final int paramIndex = i;
            Class<?> paramType = paramTypes[i];

            // 参数标题
            TextView paramTitle = new TextView(context);
            paramTitle.setText("参数 " + (i + 1) + ": " + paramType.getSimpleName());
            paramTitle.setTextSize(16);
            paramTitle.setPadding(0, 20, 0, 10);
            container.addView(paramTitle);

            // 选择方式：变量引用 / 直接输入
            RadioGroup radioGroup = new RadioGroup(context);
            radioGroup.setOrientation(RadioGroup.HORIZONTAL);

            RadioButton rbVariable = new RadioButton(context);
            rbVariable.setText("选择变量");
            rbVariable.setId(View.generateViewId());

            RadioButton rbInput = new RadioButton(context);
            rbInput.setText("直接输入");
            rbInput.setId(View.generateViewId());

            radioGroup.addView(rbVariable);
            radioGroup.addView(rbInput);
            container.addView(radioGroup);

            // 变量选择区域
            TextView varSelector = new TextView(context);
            varSelector.setText("点击选择变量...");
            varSelector.setPadding(20, 10, 20, 10);
            varSelector.setBackgroundColor(0x20000000);
            varSelector.setOnClickListener(v -> {
                showVariableSelectorForParam(paramIndex, paramType, varSelector);
            });
            container.addView(varSelector);

            // 直接输入区域
            EditText inputField = new EditText(context);
            inputField.setHint("输入 " + paramType.getSimpleName() + " 值");
            inputField.setVisibility(View.GONE);
            container.addView(inputField);

            // 监听单选变化
            radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == rbVariable.getId()) {
                    varSelector.setVisibility(View.VISIBLE);
                    inputField.setVisibility(View.GONE);
                    paramVarRefs[paramIndex] = null; // 将在变量选择时设置
                } else {
                    varSelector.setVisibility(View.GONE);
                    inputField.setVisibility(View.VISIBLE);
                    paramVarRefs[paramIndex] = null;
                }
            });

            // 默认选择变量
            rbVariable.setChecked(true);

            // 分隔线
            View divider = new View(context);
            divider.setBackgroundColor(0x30000000);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            );
            dividerParams.setMargins(0, 20, 0, 20);
            divider.setLayoutParams(dividerParams);
            container.addView(divider);
        }

        scrollView.addView(container);
        builder.setView(scrollView);

        builder.setPositiveButton("创建对象", (dialog, which) -> {
            // 收集所有参数值
            if (collectParamValues(container)) {
                createObject();
            }
        });

        builder.setNegativeButton("返回", (dialog, which) -> {
            try {
                Class<?> clazz = classLoader.loadClass(selectedClassName);
                showConstructorListDialog(clazz);
            } catch (Exception e) {
                // ignore
            }
        });

        builder.create().show();
    }

    /**
     * 显示变量选择器
     */
    private void showVariableSelectorForParam(int paramIndex, Class<?> paramType, TextView displayView) {
        VariableListDialog dialog = new VariableListDialog(context, classLoader);
        dialog.show((varName, var) -> {
            Object value = var.getValue();
            if (value != null && paramType.isInstance(value)) {
                paramVarRefs[paramIndex] = varName;
                paramValues[paramIndex] = value;
                displayView.setText("✓ " + varName + " (" + var.getTypeDisplayName() + ")");
            } else {
                Toast.makeText(context, "变量类型不匹配，需要: " + paramType.getSimpleName(),
                    Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 收集参数值
     */
    private boolean collectParamValues(LinearLayout container) {
        int childCount = container.getChildCount();
        int paramIndex = 0;

        for (int i = 0; i < childCount; i++) {
            View child = container.getChildAt(i);

            // 处理输入框
            if (child instanceof EditText) {
                EditText inputField = (EditText) child;
                if (inputField.getVisibility() == View.VISIBLE) {
                    String inputStr = inputField.getText().toString().trim();
                    if (!parseParamValue(paramIndex, paramTypes[paramIndex], inputStr)) {
                        return false;
                    }
                    paramIndex++;
                }
            }
            // 处理变量选择（检查是否有变量引用）
            else if (child instanceof TextView && !(child instanceof EditText)) {
                TextView tv = (TextView) child;
                if (tv.getText().toString().startsWith("✓")) {
                    // 变量已选择，paramValues 已在 showVariableSelectorForParam 中设置
                    paramIndex++;
                }
            }
        }

        // 验证所有参数都已设置
        for (int i = 0; i < paramTypes.length; i++) {
            if (paramValues[i] == null && paramVarRefs[i] == null) {
                Toast.makeText(context, "参数 " + (i + 1) + " 未设置", Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        return true;
    }

    /**
     * 解析参数值
     */
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
            } else if (type == byte.class || type == Byte.class) {
                paramValues[index] = Byte.parseByte(input);
            } else if (type == short.class || type == Short.class) {
                paramValues[index] = Short.parseShort(input);
            } else if (type == char.class || type == Character.class) {
                if (input.length() == 1) {
                    paramValues[index] = input.charAt(0);
                } else {
                    throw new IllegalArgumentException("需要单个字符");
                }
            } else {
                Toast.makeText(context, "不支持的参数类型: " + type.getSimpleName(),
                    Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        } catch (Exception e) {
            Toast.makeText(context, "参数 " + (index + 1) + " 解析失败: " + e.getMessage(),
                Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /**
     * 步骤4: 创建对象
     */
    private void createObject() {
        new Thread(() -> {
            try {
                Object instance = ReflectUtils.newInstance(selectedConstructor, paramValues);

                // 保存变量引用信息
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

                // 创建 RetraceableVar
                RetraceableVar var = new RetraceableVar(
                    null, // 名称自动生成
                    instance,
                    RetraceableVar.VarSource.CONSTRUCTOR
                );
                var.setConstructorParams(paramRefs, primParams);

                String varName = variableManager.addVariable(var);

                // 显示成功对话框
                showSuccessDialog(varName, instance);

            } catch (Exception e) {
                showErrorDialog(e);
            }
        }).start();
    }

    /**
     * 显示成功对话框
     */
    private void showSuccessDialog(String varName, Object instance) {
        new android.os.Handler(context.getMainLooper()).post(() -> {
            new AlertDialog.Builder(context)
                .setTitle("创建成功")
                .setMessage("对象已创建并保存为变量: " + varName + "\n\n" +
                    "类型: " + instance.getClass().getName() + "\n" +
                    "toString: " + instance.toString())
                .setPositiveButton("查看详情", (dialog, which) -> {
                    ClassStructureViewer viewer = new ClassStructureViewer(context, classLoader);
                    viewer.show(instance, instance.getClass(), varName, null);
                })
                .setNegativeButton("关闭", null)
                .create().show();
        });
    }

    /**
     * 显示错误对话框
     */
    private void showErrorDialog(Exception e) {
        new android.os.Handler(context.getMainLooper()).post(() -> {
            new AlertDialog.Builder(context)
                .setTitle("创建失败")
                .setMessage("错误: " + e.getMessage() + "\n\n" +
                    android.util.Log.getStackTraceString(e))
                .setPositiveButton("确定", null)
                .create().show();
        });
    }
}
