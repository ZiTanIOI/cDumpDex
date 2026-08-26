package com.zitan.cdumpdex.reflection;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.zitan.cdumpdex.RetraceableVar;

import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 实例扫描器
 * 通过 Hook 构造函数追踪类实例
 */
public class InstanceScanner {

    private static final String TAG = "InstanceScanner";

    // 预定义的常用类列表（在 Application.attach 时预 hook）
    private static final String[] PRE_HOOK_CLASSES = {
        "android.app.Activity",
        "android.app.Fragment",
        "androidx.fragment.app.Fragment",
        "android.view.View",
        "android.view.ViewGroup",
        "android.widget.TextView",
        "android.widget.EditText",
        "android.widget.Button",
        "android.widget.ImageView",
        "android.widget.LinearLayout",
        "android.widget.FrameLayout",
        "android.widget.RelativeLayout",
        "android.app.Dialog",
        "android.app.AlertDialog",
        "android.app.Application",
        "android.content.ContextWrapper",
        "android.content.ContextImpl",
        "android.app.Service",
        "android.content.BroadcastReceiver",
        "android.content.ContentProvider",
        "java.lang.Thread",
        "java.util.ArrayList",
        "java.util.HashMap",
        "java.util.HashSet",
        "java.lang.StringBuilder",
        "java.lang.StringBuffer"
    };

    // 实例缓存：类名 -> 实例列表
    private static final ConcurrentHashMap<String, List<WeakReference<Object>>> instanceCache =
        new ConcurrentHashMap<>();

    // 已 hook 的类集合
    private static final Set<String> hookedClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Hook 回调
    private static final XC_MethodHook constructorHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Object instance = param.thisObject;
            String className = instance.getClass().getName();

            List<WeakReference<Object>> list = instanceCache.computeIfAbsent(
                className, k -> Collections.synchronizedList(new ArrayList<>())
            );

            // 避免重复添加
            for (WeakReference<Object> ref : list) {
                if (ref.get() == instance) {
                    return;
                }
            }

            list.add(new WeakReference<>(instance));

            // 同时存储到父类的缓存
            Class<?> superClass = instance.getClass().getSuperclass();
            while (superClass != null && superClass != Object.class) {
                String superName = superClass.getName();
                List<WeakReference<Object>> superList = instanceCache.computeIfAbsent(
                    superName, k -> Collections.synchronizedList(new ArrayList<>())
                );
                superList.add(new WeakReference<>(instance));
                superClass = superClass.getSuperclass();
            }
        }
    };

    private final Context context;
    private final ClassLoader classLoader;
    private final VariableManager variableManager;

    public InstanceScanner(Context context, ClassLoader classLoader) {
        this.context = context;
        this.classLoader = classLoader;
        this.variableManager = VariableManager.getInstance(context);
    }

    /**
     * 预 Hook 常用类（在 Application.attach 时调用）
     */
    public static void preHookCommonClasses(ClassLoader classLoader) {
        for (String className : PRE_HOOK_CLASSES) {
            try {
                Class<?> clazz = classLoader.loadClass(className);
                hookClassConstructors(clazz);
            } catch (ClassNotFoundException e) {
                // 类不存在，忽略
            } catch (Exception e) {
                XposedBridge.log(TAG + ": Failed to hook " + className + " - " + e.getMessage());
            }
        }
    }

    /**
     * Hook 指定类的所有构造函数
     */
    public static void hookClassConstructors(Class<?> targetClass) {
        String className = targetClass.getName();
        if (hookedClasses.contains(className)) {
            return; // 已 hook
        }

        try {
            // 检查是否可以实例化（不是抽象类或接口）
            int modifiers = targetClass.getModifiers();
            if (Modifier.isAbstract(modifiers) || Modifier.isInterface(modifiers)) {
                return;
            }

            XposedBridge.hookAllConstructors(targetClass, constructorHook);
            hookedClasses.add(className);

            // 初始化缓存列表
            instanceCache.putIfAbsent(className, Collections.synchronizedList(new ArrayList<>()));

            XposedBridge.log(TAG + ": Hooked constructors of " + className);

        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook " + className + " - " + e.getMessage());
        }
    }

    /**
     * 获取指定类的存活实例列表
     */
    public static List<Object> getLiveInstances(String className) {
        List<WeakReference<Object>> refs = instanceCache.get(className);
        if (refs == null) {
            return Collections.emptyList();
        }

        List<Object> liveInstances = new ArrayList<>();
        List<WeakReference<Object>> toRemove = new ArrayList<>();

        synchronized (refs) {
            for (WeakReference<Object> ref : refs) {
                Object obj = ref.get();
                if (obj != null) {
                    liveInstances.add(obj);
                } else {
                    toRemove.add(ref);
                }
            }

            // 清理已被 GC 的引用
            refs.removeAll(toRemove);
        }

        return liveInstances;
    }

    /**
     * 获取已 hook 的类列表
     */
    public static Set<String> getHookedClasses() {
        return new HashSet<>(hookedClasses);
    }

    /**
     * 检查类是否已被 hook
     */
    public static boolean isClassHooked(String className) {
        return hookedClasses.contains(className);
    }

    /**
     * 显示实例扫描对话框
     */
    public void show() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("实例扫描");

        List<String> items = new ArrayList<>();
        items.add("🔍 输入类名扫描");
        items.add("📋 查看已 Hook 的类");
        items.add("─── 快速扫描 ───");
        items.add("Activity 实例");
        items.add("Fragment 实例");
        items.add("View 实例");
        items.add("Dialog 实例");
        items.add("Service 实例");

        builder.setItems(items.toArray(new CharSequence[0]), (dialog, which) -> {
            switch (which) {
                case 0:
                    showClassNameInputDialog();
                    break;
                case 1:
                    showHookedClassesDialog();
                    break;
                case 3:
                    scanAndShowInstances("android.app.Activity");
                    break;
                case 4:
                    scanAndShowInstances("android.app.Fragment", "androidx.fragment.app.Fragment");
                    break;
                case 5:
                    scanAndShowInstances("android.view.View");
                    break;
                case 6:
                    scanAndShowInstances("android.app.Dialog");
                    break;
                case 7:
                    scanAndShowInstances("android.app.Service");
                    break;
            }
        });

        builder.setNegativeButton("关闭", null);
        builder.create().show();
    }

    /**
     * 显示输入类名对话框
     */
    private void showClassNameInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("输入要扫描的类名");

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
        hint.setText("注意：只能扫描已 Hook 构造函数的类\n如果该类尚未 Hook，将自动 Hook 其构造函数");
        hint.setPadding(50, 20, 50, 10);
        container.addView(hint);
        container.addView(input);

        builder.setView(container);

        builder.setPositiveButton("扫描", (dialog, which) -> {
            String className = input.getText().toString().trim();
            if (className.isEmpty()) {
                Toast.makeText(context, "请输入类名", Toast.LENGTH_SHORT).show();
                return;
            }
            scanAndShowInstances(className);
        });

        builder.setNegativeButton("取消", null);
        builder.create().show();
    }

    /**
     * 显示已 Hook 的类列表
     */
    private void showHookedClassesDialog() {
        Set<String> hooked = getHookedClasses();

        if (hooked.isEmpty()) {
            new AlertDialog.Builder(context)
                .setTitle("已 Hook 的类")
                .setMessage("尚未 Hook 任何类的构造函数")
                .setPositiveButton("确定", null)
                .create().show();
            return;
        }

        List<String> items = new ArrayList<>(hooked);
        java.util.Collections.sort(items);

        // 显示每个类的实例数量
        List<String> displayItems = new ArrayList<>();
        for (String className : items) {
            int count = getLiveInstances(className).size();
            displayItems.add(className + " (" + count + ")");
        }

        new AlertDialog.Builder(context)
            .setTitle("已 Hook 的类 (" + items.size() + ")")
            .setItems(displayItems.toArray(new CharSequence[0]), (dialog, which) -> {
                String className = items.get(which);
                scanAndShowInstances(className);
            })
            .setNegativeButton("关闭", null)
            .create().show();
    }

    /**
     * 扫描并显示实例列表
     */
    private void scanAndShowInstances(String... classNames) {
        new Thread(() -> {
            List<InstanceInfo> allInstances = new ArrayList<>();

            for (String className : classNames) {
                // 检查是否已 hook，如果没有则尝试 hook
                if (!isClassHooked(className)) {
                    try {
                        Class<?> clazz = classLoader.loadClass(className);
                        hookClassConstructors(clazz);
                    } catch (ClassNotFoundException e) {
                        continue;
                    } catch (Exception e) {
                        // hook 失败
                    }
                }

                List<Object> instances = getLiveInstances(className);
                for (Object instance : instances) {
                    allInstances.add(new InstanceInfo(instance));
                }
            }

            new android.os.Handler(context.getMainLooper()).post(() -> {
                showInstanceListDialog(allInstances);
            });
        }).start();
    }

    /**
     * 显示实例列表对话框
     */
    private void showInstanceListDialog(List<InstanceInfo> instances) {
        if (instances.isEmpty()) {
            new AlertDialog.Builder(context)
                .setTitle("扫描结果")
                .setMessage("未找到任何实例\n\n可能的原因：\n" +
                    "1. 该类尚未创建任何实例\n" +
                    "2. 该类的构造函数尚未被 Hook\n" +
                    "3. 所有实例已被垃圾回收")
                .setPositiveButton("确定", null)
                .create().show();
            return;
        }

        List<String> items = new ArrayList<>();
        for (InstanceInfo info : instances) {
            items.add(info.toString());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("找到 " + instances.size() + " 个实例");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
            android.R.layout.simple_list_item_1, items);

        builder.setAdapter(adapter, (dialog, which) -> {
            InstanceInfo selected = instances.get(which);
            showInstanceDetailDialog(selected);
        });

        builder.setNegativeButton("关闭", null);
        builder.create().show();
    }

    /**
     * 显示实例详情对话框
     */
    private void showInstanceDetailDialog(InstanceInfo info) {
        Object instance = info.instance;
        Class<?> clazz = instance.getClass();

        List<String> items = new ArrayList<>();
        items.add("类型: " + clazz.getName());
        items.add("hashCode: " + Integer.toHexString(instance.hashCode()));
        items.add("toString: " + truncate(instance.toString(), 100));
        items.add("─ ─ ─ ─ ─ ─ ─ ─ ─ ─");
        items.add("💾 保存到变量列表");
        items.add("📋 查看类结构");
        items.add("📋 复制 toString()");

        new AlertDialog.Builder(context)
            .setTitle("实例详情")
            .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> {
                switch (which) {
                    case 4: // 保存到变量
                        saveInstanceToVariable(info);
                        break;
                    case 5: // 查看类结构
                        showClassStructure(info);
                        break;
                    case 6: // 复制 toString
                        copyToString(instance);
                        break;
                }
            })
            .setNegativeButton("关闭", null)
            .create().show();
    }

    /**
     * 保存实例到变量
     */
    private void saveInstanceToVariable(InstanceInfo info) {
        RetraceableVar var = new RetraceableVar(
            null,
            info.instance,
            RetraceableVar.VarSource.INSTANCE_SCAN
        );

        String varName = variableManager.addVariable(var);
        Toast.makeText(context, "已保存为: " + varName, Toast.LENGTH_SHORT).show();
    }

    /**
     * 显示类结构
     */
    private void showClassStructure(InstanceInfo info) {
        ClassStructureViewer viewer = new ClassStructureViewer(context, classLoader);
        viewer.show(info.instance, info.instance.getClass());
    }

    /**
     * 复制 toString
     */
    private void copyToString(Object instance) {
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText(
            "toString", instance.toString());
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show();
    }

    /**
     * 截断字符串
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return "null";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "...";
    }

    /**
     * 实例信息
     */
    private static class InstanceInfo {
        final Object instance;
        final Class<?> type;
        final String displayString;

        InstanceInfo(Object instance) {
            this.instance = instance;
            this.type = instance.getClass();

            String str = instance.toString();
            if (str.length() > 50) {
                str = str.substring(0, 50) + "...";
            }
            this.displayString = type.getSimpleName() + "@" +
                Integer.toHexString(instance.hashCode()) + ": " + str;
        }

        @Override
        public String toString() {
            return displayString;
        }
    }
}
