package com.zitan.cdumpdex.reflection;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.zitan.cdumpdex.R;
import com.zitan.cdumpdex.ReflectUtils;
import com.zitan.cdumpdex.RetraceableVar;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 类结构查看器
 * 展示类的继承链、接口、字段和方法
 */
public class ClassStructureViewer {

    private final Context context;
    private final VariableManager variableManager;
    private final ClassLoader classLoader;

    public ClassStructureViewer(Context context, ClassLoader classLoader) {
        this.context = context;
        this.classLoader = classLoader;
        this.variableManager = VariableManager.getInstance(context);
    }

    /**
     * 显示类结构查看对话框
     * @param instance 要查看的实例（可为 null，仅查看类结构）
     * @param clazz 要查看的类
     */
    public void show(Object instance, Class<?> clazz) {
        show(instance, clazz, null, null);
    }

    /**
     * 显示类结构查看对话框
     * @param instance 要查看的实例
     * @param clazz 要查看的类
     * @param varName 变量名（可为 null）
     * @param onMethodSelected 方法选择回调
     */
    public void show(Object instance, Class<?> clazz, String varName, OnMethodSelectedListener onMethodSelected) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("类结构: " + clazz.getSimpleName());

        // 构建选项菜单
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        // 1. 继承链
        items.add("▶ 继承链");
        actions.add(() -> showInheritanceChain(clazz));

        // 2. 实现的接口
        items.add("▶ 实现的接口");
        actions.add(() -> showInterfaces(clazz));

        // 3. 所有字段
        items.add("▶ 所有字段");
        actions.add(() -> showFields(instance, clazz, varName));

        // 4. 所有方法
        items.add("▶ 所有方法");
        actions.add(() -> showMethods(instance, clazz, varName, onMethodSelected));

        // 5. 所有构造函数
        items.add("▶ 构造函数");
        actions.add(() -> showConstructors(clazz));

        // 6. 如果有实例，显示基本信息
        if (instance != null) {
            items.add("▶ 实例信息");
            actions.add(() -> showInstanceInfo(instance, varName));
        }

        // 如果有变量名，显示保存/复制选项
        if (varName != null && !varName.isEmpty()) {
            items.add("─ ─ ─ ─ ─ ─ ─ ─ ─ ─");
            actions.add(() -> {});

            items.add("📋 复制变量值toString()");
            actions.add(() -> copyValueToString(instance));

            items.add("🗑 删除此变量");
            actions.add(() -> deleteVariable(varName));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
            android.R.layout.simple_list_item_1, items);

        builder.setAdapter(adapter, (dialog, which) -> {
            if (which >= 0 && which < actions.size()) {
                actions.get(which).run();
            }
        });

        builder.setNegativeButton("关闭", null);
        builder.create().show();
    }

    /**
     * 显示输入类名对话框
     */
    public void showClassNameInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("输入类名");

        final EditText input = new EditText(context);
        input.setHint("com.example.MyClass");
        input.setText("");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(50, 20, 50, 20);
        input.setLayoutParams(lp);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(input);

        builder.setView(container);

        builder.setPositiveButton("查看", (dialog, which) -> {
            String className = input.getText().toString().trim();
            if (className.isEmpty()) {
                Toast.makeText(context, "请输入类名", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                Class<?> clazz = classLoader.loadClass(className);
                show(null, clazz);
            } catch (ClassNotFoundException e) {
                Toast.makeText(context, "找不到类: " + className, Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("取消", null);
        builder.create().show();
    }

    /**
     * 显示继承链
     */
    private void showInheritanceChain(Class<?> clazz) {
        List<Class<?>> chain = ReflectUtils.getInheritanceChain(clazz);

        StringBuilder sb = new StringBuilder();
        for (int i = chain.size() - 1; i >= 0; i--) {
            for (int j = i; j < chain.size() - 1; j++) {
                sb.append("  ");
            }
            if (i == chain.size() - 1) {
                sb.append("└─ ");
            } else if (i == 0) {
                sb.append("┌─ ");
            } else {
                sb.append("├─ ");
            }
            sb.append(chain.get(i).getName());
            sb.append("\n");
        }

        showTextDialog("继承链", sb.toString());
    }

    /**
     * 显示实现的接口
     */
    private void showInterfaces(Class<?> clazz) {
        List<Class<?>> interfaces = ReflectUtils.getImplementedInterfaces(clazz);
        List<Class<?>> allInterfaces = new ArrayList<>();

        // 获取包括父类的所有接口
        Class<?> current = clazz;
        while (current != null) {
            for (Class<?> iface : current.getInterfaces()) {
                if (!allInterfaces.contains(iface)) {
                    allInterfaces.add(iface);
                }
            }
            current = current.getSuperclass();
        }

        if (allInterfaces.isEmpty()) {
            showTextDialog("实现的接口", "无");
            return;
        }

        List<String> items = new ArrayList<>();
        for (Class<?> iface : allInterfaces) {
            items.add(iface.getName());
        }

        showListDialog("实现的接口", items, (pos, item) -> {
            try {
                Class<?> ifaceClass = classLoader.loadClass(item);
                show(null, ifaceClass);
            } catch (ClassNotFoundException e) {
                Toast.makeText(context, "加载接口失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 显示所有字段
     */
    private void showFields(Object instance, Class<?> clazz, String varName) {
        List<Field> fields = ReflectUtils.getAllFields(clazz);

        if (fields.isEmpty()) {
            showTextDialog("字段", "无字段");
            return;
        }

        List<String> items = new ArrayList<>();
        List<Field> fieldList = new ArrayList<>();

        for (Field field : fields) {
            String modifier = ReflectUtils.getModifierString(field.getModifiers());
            String type = field.getType().getSimpleName();
            String name = field.getName();

            // 尝试获取值
            String valueStr = "";
            if (instance != null || Modifier.isStatic(field.getModifiers())) {
                try {
                    Object value = ReflectUtils.getFieldValue(field, instance);
                    valueStr = " = " + ReflectUtils.formatValue(value);
                } catch (Exception e) {
                    valueStr = " = <无法访问>";
                }
            }

            items.add(String.format("%s%s %s%s", modifier.isEmpty() ? "" : modifier + " ", type, name, valueStr));
            fieldList.add(field);
        }

        showListDialog("字段 (" + items.size() + ")", items, (pos, item) -> {
            Field selectedField = fieldList.get(pos);
            showFieldDetail(instance, selectedField, varName);
        });
    }

    /**
     * 显示字段详情
     */
    private void showFieldDetail(Object instance, Field field, String varName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("字段: " + field.getName());

        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        // 字段信息
        items.add("类型: " + field.getType().getName());
        actions.add(() -> {});

        items.add("修饰符: " + ReflectUtils.getModifierString(field.getModifiers()));
        actions.add(() -> {});

        items.add("声明类: " + field.getDeclaringClass().getName());
        actions.add(() -> {});

        items.add("─ ─ ─ ─ ─ ─ ─ ─ ─ ─");
        actions.add(() -> {});

        // 获取值
        boolean isStatic = Modifier.isStatic(field.getModifiers());
        if (instance != null || isStatic) {
            try {
                Object value = ReflectUtils.getFieldValue(field, instance);
                items.add("当前值: " + ReflectUtils.formatValue(value));
                actions.add(() -> showFieldValueDetail(value, field.getType()));

                items.add("📋 复制值");
                actions.add(() -> copyToClipboard(field.getName(), String.valueOf(value)));

                items.add("💾 保存为新变量");
                actions.add(() -> saveFieldValueAsVariable(field, instance, value, varName));
            } catch (Exception e) {
                items.add("当前值: <获取失败: " + e.getMessage() + ">");
                actions.add(() -> {});
            }
        } else {
            items.add("当前值: <需要实例>");
            actions.add(() -> {});
        }

        builder.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, items),
            (dialog, which) -> {
                if (which >= 0 && which < actions.size()) {
                    actions.get(which).run();
                }
            });

        builder.setNegativeButton("关闭", null);
        builder.create().show();
    }

    /**
     * 显示字段值的详情
     */
    private void showFieldValueDetail(Object value, Class<?> declaredType) {
        if (value == null) {
            showTextDialog("值", "null");
            return;
        }

        Class<?> actualType = value.getClass();
        if (ReflectUtils.isPrimitiveOrWrapper(actualType) || value instanceof String) {
            showTextDialog("值", ReflectUtils.formatValue(value));
        } else {
            // 对于对象类型，显示其类结构
            show(value, actualType);
        }
    }

    /**
     * 显示所有方法
     */
    private void showMethods(Object instance, Class<?> clazz, String varName, OnMethodSelectedListener listener) {
        List<Method> methods = ReflectUtils.getAllMethods(clazz);

        if (methods.isEmpty()) {
            showTextDialog("方法", "无方法");
            return;
        }

        List<String> items = new ArrayList<>();
        List<Method> methodList = new ArrayList<>();

        for (Method method : methods) {
            String signature = ReflectUtils.getMethodSignature(method);
            items.add(signature);
            methodList.add(method);
        }

        showListDialog("方法 (" + items.size() + ")", items, (pos, item) -> {
            Method selectedMethod = methodList.get(pos);
            showMethodDetail(instance, selectedMethod, varName, listener);
        });
    }

    /**
     * 显示方法详情
     */
    private void showMethodDetail(Object instance, Method method, String varName, OnMethodSelectedListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("方法: " + method.getName());

        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        // 方法信息
        items.add("返回类型: " + method.getReturnType().getName());
        actions.add(() -> {});

        items.add("修饰符: " + ReflectUtils.getModifierString(method.getModifiers()));
        actions.add(() -> {});

        items.add("声明类: " + method.getDeclaringClass().getName());
        actions.add(() -> {});

        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length > 0) {
            items.add("参数类型: " + ReflectUtils.formatParameterTypes(paramTypes));
        } else {
            items.add("参数: 无");
        }
        actions.add(() -> {});

        items.add("─ ─ ─ ─ ─ ─ ─ ─ ─ ─");
        actions.add(() -> {});

        // 调用方法
        items.add("▶ 调用此方法");
        actions.add(() -> {
            if (listener != null) {
                listener.onMethodSelected(method, instance, varName);
            }
        });

        builder.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, items),
            (dialog, which) -> {
                if (which >= 0 && which < actions.size()) {
                    actions.get(which).run();
                }
            });

        builder.setNegativeButton("关闭", null);
        builder.create().show();
    }

    /**
     * 显示所有构造函数
     */
    private void showConstructors(Class<?> clazz) {
        List<Constructor<?>> constructors = ReflectUtils.getAllConstructors(clazz);

        if (constructors.isEmpty()) {
            showTextDialog("构造函数", "无构造函数");
            return;
        }

        List<String> items = new ArrayList<>();
        List<Constructor<?>> constructorList = new ArrayList<>();

        for (Constructor<?> constructor : constructors) {
            String signature = ReflectUtils.getConstructorSignature(constructor);
            items.add(signature);
            constructorList.add(constructor);
        }

        showListDialog("构造函数 (" + items.size() + ")", items, (pos, item) -> {
            Constructor<?> selected = constructorList.get(pos);
            showConstructorDetail(selected);
        });
    }

    /**
     * 显示构造函数详情
     */
    private void showConstructorDetail(Constructor<?> constructor) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("构造函数");

        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        items.add("声明类: " + constructor.getDeclaringClass().getName());
        actions.add(() -> {});

        items.add("修饰符: " + ReflectUtils.getModifierString(constructor.getModifiers()));
        actions.add(() -> {});

        Class<?>[] paramTypes = constructor.getParameterTypes();
        if (paramTypes.length > 0) {
            items.add("参数类型: " + ReflectUtils.formatParameterTypes(paramTypes));
        } else {
            items.add("参数: 无");
        }
        actions.add(() -> {});

        items.add("─ ─ ─ ─ ─ ─ ─ ─ ─ ─");
        actions.add(() -> {});

        items.add("▶ 使用此构造函数创建对象");
        actions.add(() -> {
            new CreateObjectDialog(context, classLoader)
                .show(constructor.getDeclaringClass().getName());
        });

        builder.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, items),
            (dialog, which) -> {
                if (which >= 0 && which < actions.size()) {
                    actions.get(which).run();
                }
            });

        builder.setNegativeButton("关闭", null);
        builder.create().show();
    }

    /**
     * 显示实例信息
     */
    private void showInstanceInfo(Object instance, String varName) {
        if (instance == null) {
            showTextDialog("实例信息", "实例为 null");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("变量名: ").append(varName != null ? varName : "<未命名>").append("\n");
        sb.append("类型: ").append(instance.getClass().getName()).append("\n");
        sb.append("hashCode: ").append(Integer.toHexString(instance.hashCode())).append("\n");
        sb.append("toString: ").append(instance.toString());

        showTextDialog("实例信息", sb.toString());
    }

    /**
     * 保存字段值为变量
     */
    private void saveFieldValueAsVariable(Field field, Object instance, Object value, String instanceVarRef) {
        if (value == null) {
            Toast.makeText(context, "值为 null，未保存", Toast.LENGTH_SHORT).show();
            return;
        }

        RetraceableVar var = new RetraceableVar(
            null, // 名称自动生成
            value,
            Modifier.isStatic(field.getModifiers()) ?
                RetraceableVar.VarSource.STATIC_FIELD : RetraceableVar.VarSource.FIELD_ACCESS
        );
        var.setFieldInfo(
            field.getDeclaringClass().getName(),
            field.getName(),
            Modifier.isStatic(field.getModifiers()) ? null : instanceVarRef
        );

        String savedName = variableManager.addVariable(var);
        Toast.makeText(context, "已保存为: " + savedName, Toast.LENGTH_SHORT).show();
    }

    /**
     * 复制到剪贴板
     */
    private void copyToClipboard(String label, String text) {
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show();
    }

    /**
     * 复制值的 toString
     */
    private void copyValueToString(Object instance) {
        if (instance != null) {
            copyToClipboard("value", instance.toString());
        }
    }

    /**
     * 删除变量
     */
    private void deleteVariable(String varName) {
        new AlertDialog.Builder(context)
            .setTitle("确认删除")
            .setMessage("确定要删除变量 '" + varName + "' 吗？")
            .setPositiveButton("删除", (dialog, which) -> {
                variableManager.removeVariable(varName);
                Toast.makeText(context, "已删除: " + varName, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .create().show();
    }

    // ==================== 辅助对话框方法 ====================

    private void showTextDialog(String title, String content) {
        new AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(content)
            .setPositiveButton("复制", (dialog, which) -> copyToClipboard(title, content))
            .setNegativeButton("关闭", null)
            .create().show();
    }

    private void showListDialog(String title, List<String> items, OnItemClickListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
            android.R.layout.simple_list_item_1, items);

        builder.setAdapter(adapter, (dialog, which) -> {
            listener.onItemClick(which, items.get(which));
        });

        builder.setNegativeButton("关闭", null);
        builder.create().show();
    }

    // ==================== 接口定义 ====================

    public interface OnItemClickListener {
        void onItemClick(int position, String item);
    }

    public interface OnMethodSelectedListener {
        void onMethodSelected(Method method, Object instance, String varName);
    }
}
