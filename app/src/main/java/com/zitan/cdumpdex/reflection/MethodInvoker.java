package com.zitan.cdumpdex.reflection;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.TextView;
import android.widget.Toast;

import com.zitan.cdumpdex.ReflectUtils;
import com.zitan.cdumpdex.RetraceableVar;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 方法调用器
 * 支持选择方法、配置参数（实例/静态）、调用并处理返回值
 */
public class MethodInvoker {

    private final Context context;
    private final ClassLoader classLoader;
    private final VariableManager variableManager;

    // 当前状态
    private String className;
    private Class<?> targetClass;
    private Method selectedMethod;
    private Object instance;
    private String instanceVarName;

    public MethodInvoker(Context context, ClassLoader classLoader) {
        this.context = context;
        this.classLoader = classLoader;
        this.variableManager = VariableManager.getInstance(context);
    }

    /**
     * 显示方法调用对话框（输入类名开始）
     */
    public void show() {
        showClassNameInputDialog();
    }

    /**
     * 显示方法调用对话框（已有实例）
     */
    public void show(Object instance, String varName) {
        this.instance = instance;
        this.instanceVarName = varName;
        this.className = instance.getClass().getName();
        this.targetClass = instance.getClass();
        showMethodListDialog();
    }

    /**
     * 显示方法调用对话框（已有方法）
     */
    public void show(Method method, Object instance, String varName) {
        this.selectedMethod = method;
        this.instance = instance;
        this.instanceVarName = varName;
        this.className = method.getDeclaringClass().getName();
        this.targetClass = method.getDeclaringClass();
        showMethodInvokeDialog();
    }

    /**
     * 步骤1: 输入类名对话框
     */
    private void showClassNameInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("调用方法 - 输入类名");

        final EditText input = new EditText(context);
        input.setHint("com.example.MyClass");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(50, 20, 50, 20);
        input.setLayoutParams(lp);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView hint = new TextView(context);
        hint.setText("输入完整的类名（包括包名）\n可以调用静态方法或选择实例后调用实例方法");
        hint.setPadding(50, 20, 50, 10);
        container.addView(hint);
        container.addView(input);

        builder.setView(container);

        builder.setPositiveButton("下一步", (dialog, which) -> {
            className = input.getText().toString().trim();
            if (className.isEmpty()) {
                Toast.makeText(context, "请输入类名", Toast.LENGTH_SHORT).show();
                return;
            }
            loadClassAndShowMethods();
        });

        builder.setNegativeButton("取消", null);
        builder.create().show();
    }

    /**
     * 加载类并显示方法列表
     */
    private void loadClassAndShowMethods() {
        try {
            targetClass = classLoader.loadClass(className);
            showMethodListDialog();
        } catch (ClassNotFoundException e) {
            Toast.makeText(context, "找不到类: " + className, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context, "加载类失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 步骤2: 显示方法列表
     */
    private void showMethodListDialog() {
        List<Method> methods = ReflectUtils.getAllMethods(targetClass);

        if (methods.isEmpty()) {
            Toast.makeText(context, "该类没有可访问的方法", Toast.LENGTH_SHORT).show();
            return;
        }

        // 分类：静态方法 / 实例方法
        List<String> staticMethods = new ArrayList<>();
        List<Method> staticMethodList = new ArrayList<>();
        List<String> instanceMethods = new ArrayList<>();
        List<Method> instanceMethodList = new ArrayList<>();

        for (Method method : methods) {
            String signature = ReflectUtils.getMethodSignature(method);
            if (Modifier.isStatic(method.getModifiers())) {
                staticMethods.add("[静态] " + signature);
                staticMethodList.add(method);
            } else {
                instanceMethods.add(signature);
                instanceMethodList.add(method);
            }
        }

        List<String> allItems = new ArrayList<>();
        List<Method> allMethods = new ArrayList<>();

        // 先添加静态方法
        if (!staticMethods.isEmpty()) {
            allItems.add("─── 静态方法 (" + staticMethods.size() + ") ───");
            allMethods.add(null); // 分隔符
            allItems.addAll(staticMethods);
            allMethods.addAll(staticMethodList);
        }

        // 再添加实例方法
        if (!instanceMethods.isEmpty()) {
            allItems.add("─── 实例方法 (" + instanceMethods.size() + ") ───");
            allMethods.add(null); // 分隔符
            allItems.addAll(instanceMethods);
            allMethods.addAll(instanceMethodList);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("选择方法 (" + methods.size() + ")");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
            android.R.layout.simple_list_item_1, allItems);

        builder.setAdapter(adapter, (dialog, which) -> {
            Method selected = allMethods.get(which);
            if (selected == null) return; // 分隔符

            selectedMethod = selected;

            // 检查是否需要实例
            if (!Modifier.isStatic(selected.getModifiers()) && instance == null) {
                // 需要选择实例
                showInstanceSelectorDialog();
            } else {
                showMethodInvokeDialog();
            }
        });

        builder.setNegativeButton("返回", (dialog, which) -> {
            if (instance == null) {
                showClassNameInputDialog();
            }
        });

        builder.create().show();
    }

    /**
     * 显示实例选择对话框
     */
    private void showInstanceSelectorDialog() {
        // 先检查是否有匹配类型的变量
        List<String> matchingVars = new ArrayList<>();
        List<Object> matchingValues = new ArrayList<>();

        for (String varName : variableManager.getVariableNames()) {
            RetraceableVar var = variableManager.getVariable(varName);
            if (var != null && var.getValue() != null) {
                if (targetClass.isInstance(var.getValue())) {
                    matchingVars.add(varName);
                    matchingValues.add(var.getValue());
                }
            }
        }

        // 检查实例扫描缓存
        List<Object> scannedInstances = variableManager.getLiveInstances(className);
        for (Object inst : scannedInstances) {
            if (!matchingValues.contains(inst)) {
                matchingVars.add("[扫描] " + inst.getClass().getSimpleName() + "@" + Integer.toHexString(inst.hashCode()));
                matchingValues.add(inst);
            }
        }

        if (matchingVars.isEmpty()) {
            new AlertDialog.Builder(context)
                .setTitle("选择实例")
                .setMessage("没有找到类型为 " + targetClass.getSimpleName() + " 的实例\n\n" +
                    "请先在'保存的变量'中添加该类型的实例，或使用'实例扫描'功能。")
                .setPositiveButton("确定", null)
                .create().show();
            return;
        }

        new AlertDialog.Builder(context)
            .setTitle("选择实例")
            .setItems(matchingVars.toArray(new CharSequence[0]), (dialog, which) -> {
                instance = matchingValues.get(which);
                String varName = matchingVars.get(which);
                if (varName.startsWith("[扫描]")) {
                    instanceVarName = null;
                } else {
                    instanceVarName = varName;
                }
                showMethodInvokeDialog();
            })
            .setNegativeButton("取消", null)
            .create().show();
    }

    /**
     * 步骤3: 显示方法调用对话框
     */
    private void showMethodInvokeDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("调用: " + selectedMethod.getName());

        Class<?>[] paramTypes = selectedMethod.getParameterTypes();

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(30, 30, 30, 30);

        // 显示方法信息
        TextView infoText = new TextView(context);
        StringBuilder info = new StringBuilder();
        info.append("方法: ").append(ReflectUtils.getMethodSignature(selectedMethod)).append("\n");
        info.append("返回类型: ").append(selectedMethod.getReturnType().getSimpleName()).append("\n");

        if (Modifier.isStatic(selectedMethod.getModifiers())) {
            info.append("调用方式: 静态方法");
        } else {
            info.append("调用方式: 实例方法\n");
            info.append("实例: ").append(instanceVarName != null ? instanceVarName : instance.getClass().getSimpleName());
        }

        infoText.setText(info.toString());
        infoText.setPadding(0, 0, 0, 20);
        container.addView(infoText);

        // 如果有参数，显示参数配置
        final Object[] paramValues = new Object[paramTypes.length];
        final String[] paramVarRefs = new String[paramTypes.length];

        if (paramTypes.length > 0) {
            TextView paramTitle = new TextView(context);
            paramTitle.setText("参数配置:");
            paramTitle.setTextSize(16);
            paramTitle.setPadding(0, 20, 0, 10);
            container.addView(paramTitle);

            for (int i = 0; i < paramTypes.length; i++) {
                final int paramIndex = i;
                Class<?> paramType = paramTypes[i];

                // 参数标题
                TextView paramLabel = new TextView(context);
                paramLabel.setText("参数 " + (i + 1) + ": " + paramType.getSimpleName());
                paramLabel.setPadding(0, 10, 0, 5);
                container.addView(paramLabel);

                // 输入方式选择
                LinearLayout paramRow = new LinearLayout(context);
                paramRow.setOrientation(LinearLayout.HORIZONTAL);

                EditText inputField = new EditText(context);
                inputField.setHint("直接输入");
                inputField.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                TextView varSelector = new TextView(context);
                varSelector.setText("选择变量");
                varSelector.setPadding(20, 10, 10, 10);
                varSelector.setBackgroundColor(0x20000000);

                varSelector.setOnClickListener(v -> {
                    VariableListDialog dialog = new VariableListDialog(context, classLoader);
                    dialog.show((varName, var) -> {
                        Object value = var.getValue();
                        if (value != null && paramType.isInstance(value)) {
                            paramValues[paramIndex] = value;
                            paramVarRefs[paramIndex] = varName;
                            varSelector.setText("✓ " + varName);
                            inputField.setText("");
                        } else {
                            Toast.makeText(context, "类型不匹配，需要: " + paramType.getSimpleName(),
                                Toast.LENGTH_SHORT).show();
                        }
                    });
                });

                // 输入监听
                inputField.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) {
                        paramValues[paramIndex] = null;
                        paramVarRefs[paramIndex] = null;
                        varSelector.setText("选择变量");
                    }
                });

                inputField.setOnEditorActionListener((v, actionId, event) -> {
                    String input = inputField.getText().toString().trim();
                    if (!input.isEmpty()) {
                        if (parseParamValue(paramIndex, paramType, input, paramValues)) {
                            paramVarRefs[paramIndex] = null;
                            varSelector.setText("选择变量");
                        }
                    }
                    return false;
                });

                paramRow.addView(inputField);
                paramRow.addView(varSelector);
                container.addView(paramRow);

                // 分隔线
                View divider = new View(context);
                divider.setBackgroundColor(0x20000000);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1
                );
                dividerParams.setMargins(0, 10, 0, 10);
                divider.setLayoutParams(dividerParams);
                container.addView(divider);
            }
        }

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(container);
        builder.setView(scrollView);

        builder.setPositiveButton("调用", (dialog, which) -> {
            // 收集输入框的值
            if (collectInputValues(container, paramTypes, paramValues)) {
                invokeMethod(paramValues, paramVarRefs);
            }
        });

        builder.setNegativeButton("返回", (dialog, which) -> {
            showMethodListDialog();
        });

        builder.create().show();
    }

    /**
     * 收集输入值
     */
    private boolean collectInputValues(LinearLayout container, Class<?>[] paramTypes, Object[] paramValues) {
        int paramIndex = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                for (int j = 0; j < row.getChildCount(); j++) {
                    View rowChild = row.getChildAt(j);
                    if (rowChild instanceof EditText) {
                        EditText input = (EditText) rowChild;
                        String text = input.getText().toString().trim();
                        if (!text.isEmpty() && paramValues[paramIndex] == null) {
                            if (!parseParamValue(paramIndex, paramTypes[paramIndex], text, paramValues)) {
                                return false;
                            }
                        }
                        paramIndex++;
                    }
                }
            }
        }

        // 检查所有参数都已设置
        for (int i = 0; i < paramTypes.length; i++) {
            if (paramValues[i] == null) {
                // 对于基本类型，允许默认值
                if (paramTypes[i].isPrimitive()) {
                    paramValues[i] = getDefaultValue(paramTypes[i]);
                } else {
                    // 允许 null
                    paramValues[i] = null;
                }
            }
        }

        return true;
    }

    /**
     * 解析参数值
     */
    private boolean parseParamValue(int index, Class<?> type, String input, Object[] paramValues) {
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
                paramValues[index] = null;
            }
            return true;
        } catch (Exception e) {
            Toast.makeText(context, "参数 " + (index + 1) + " 解析失败: " + e.getMessage(),
                Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /**
     * 获取基本类型默认值
     */
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

    /**
     * 调用方法
     */
    private void invokeMethod(Object[] paramValues, String[] paramVarRefs) {
        new Thread(() -> {
            try {
                Object result = ReflectUtils.invokeMethod(selectedMethod, instance, paramValues);

                new android.os.Handler(context.getMainLooper()).post(() -> {
                    showResultDialog(result, paramVarRefs);
                });

            } catch (Exception e) {
                new android.os.Handler(context.getMainLooper()).post(() -> {
                    showErrorDialog(e);
                });
            }
        }).start();
    }

    /**
     * 显示结果对话框
     */
    private void showResultDialog(Object result, String[] paramVarRefs) {
        Class<?> returnType = selectedMethod.getReturnType();

        if (returnType == void.class) {
            new AlertDialog.Builder(context)
                .setTitle("调用完成")
                .setMessage("方法执行完成，无返回值")
                .setPositiveButton("确定", null)
                .create().show();
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append("返回类型: ").append(returnType.getSimpleName()).append("\n\n");

        if (result == null) {
            message.append("返回值: null");
        } else {
            message.append("返回值:\n");
            String resultStr = ReflectUtils.formatValue(result);
            if (resultStr.length() > 500) {
                resultStr = resultStr.substring(0, 500) + "...";
            }
            message.append(resultStr);
        }

        new AlertDialog.Builder(context)
            .setTitle("调用成功")
            .setMessage(message.toString())
            .setPositiveButton("确定", null)
            .setNeutralButton("复制值", (dialog, which) -> {
                copyToClipboard("返回值", result != null ? result.toString() : "null");
            })
            .setNegativeButton("保存为变量", (dialog, which) -> {
                saveResultAsVariable(result, paramVarRefs);
            })
            .create().show();
    }

    /**
     * 保存返回值为变量
     */
    private void saveResultAsVariable(Object result, String[] paramVarRefs) {
        if (result == null) {
            Toast.makeText(context, "返回值为 null，未保存", Toast.LENGTH_SHORT).show();
            return;
        }

        RetraceableVar var = new RetraceableVar(
            null,
            result,
            RetraceableVar.VarSource.METHOD_RETURN
        );
        var.setMethodInfo(
            selectedMethod.getDeclaringClass().getName(),
            selectedMethod.getName(),
            instanceVarName
        );

        String varName = variableManager.addVariable(var);
        Toast.makeText(context, "已保存为: " + varName, Toast.LENGTH_SHORT).show();
    }

    /**
     * 显示错误对话框
     */
    private void showErrorDialog(Exception e) {
        new AlertDialog.Builder(context)
            .setTitle("调用失败")
            .setMessage("错误: " + e.getMessage() + "\n\n" +
                android.util.Log.getStackTraceString(e))
            .setPositiveButton("确定", null)
            .create().show();
    }

    /**
     * 复制到剪贴板
     */
    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show();
    }
}
