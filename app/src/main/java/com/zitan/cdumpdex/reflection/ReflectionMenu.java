package com.zitan.cdumpdex.reflection;

import android.app.AlertDialog;
import android.content.Context;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import com.zitan.cdumpdex.RetraceableVar;

import java.util.List;

/**
 * 反射功能菜单入口
 * 提供统一的入口访问所有反射相关功能
 */
public class ReflectionMenu {

    private static final String TAG = "ReflectionMenu";

    // 菜单项常量
    private static final int MENU_CREATE_OBJECT = 0;
    private static final int MENU_INSTANCE_SCAN = 1;
    private static final int MENU_SAVED_VARIABLES = 2;
    private static final int MENU_CURRENT_CONTEXT = 3;
    private static final int MENU_INVOKE_METHOD = 4;
    private static final int MENU_VIEW_CLASS = 5;

    private final Context context;
    private final ClassLoader classLoader;
    private final VariableManager variableManager;

    // 功能模块
    private CreateObjectDialog createObjectDialog;
    private InstanceScanner instanceScanner;
    private VariableListDialog variableListDialog;
    private MethodInvoker methodInvoker;
    private ClassStructureViewer classStructureViewer;

    public ReflectionMenu(Context context, ClassLoader classLoader) {
        this.context = context;
        this.classLoader = classLoader;
        this.variableManager = VariableManager.getInstance(context);

        // 初始化功能模块
        initModules();
    }

    private void initModules() {
        createObjectDialog = new CreateObjectDialog(context, classLoader);
        instanceScanner = new InstanceScanner(context, classLoader);
        variableListDialog = new VariableListDialog(context, classLoader);
        methodInvoker = new MethodInvoker(context, classLoader);
        classStructureViewer = new ClassStructureViewer(context, classLoader);

        variableListDialog.setClassStructureViewer(classStructureViewer);
    }

    /**
     * 显示反射功能主菜单
     */
    public void show() {
        String[] menuItems = {
            "🔨 创建对象",
            "🔍 获取内存实例",
            "📦 保存的变量",
            "📍 当前 Context",
            "⚡ 调用方法",
            "📖 查看类结构"
        };

        // 显示变量数量
        int varCount = variableManager.getVariableNames().size();

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("反射工具" + (varCount > 0 ? " (" + varCount + " 个变量)" : ""));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
            android.R.layout.simple_list_item_1, menuItems);

        builder.setAdapter(adapter, (dialog, which) -> {
            handleMenuClick(which);
        });

        builder.setNegativeButton("关闭", null);
        builder.create().show();
    }

    /**
     * 处理菜单点击
     */
    private void handleMenuClick(int menuId) {
        switch (menuId) {
            case MENU_CREATE_OBJECT:
                showCreateObject();
                break;

            case MENU_INSTANCE_SCAN:
                showInstanceScan();
                break;

            case MENU_SAVED_VARIABLES:
                showSavedVariables();
                break;

            case MENU_CURRENT_CONTEXT:
                showCurrentContext();
                break;

            case MENU_INVOKE_METHOD:
                showInvokeMethod();
                break;

            case MENU_VIEW_CLASS:
                showViewClass();
                break;
        }
    }

    /**
     * 创建对象
     */
    private void showCreateObject() {
        createObjectDialog.show();
    }

    /**
     * 实例扫描
     */
    private void showInstanceScan() {
        instanceScanner.show();
    }

    /**
     * 保存的变量
     */
    private void showSavedVariables() {
        variableListDialog.show();
    }

    /**
     * 当前 Context
     */
    private void showCurrentContext() {
        showContextDialog();
    }

    /**
     * 调用方法
     */
    private void showInvokeMethod() {
        methodInvoker.show();
    }

    /**
     * 查看类结构
     */
    private void showViewClass() {
        classStructureViewer.showClassNameInputDialog();
    }

    /**
     * 显示当前 Context 信息
     */
    private void showContextDialog() {
        // 获取 Context 相关信息
        String contextInfo = buildContextInfo();

        // 获取 Activity 栈信息
        String activityStack = getActivityStackInfo();

        String fullInfo = contextInfo + "\n\n" + activityStack;

        new AlertDialog.Builder(context)
            .setTitle("当前 Context 信息")
            .setMessage(fullInfo)
            .setPositiveButton("保存到变量", (dialog, which) -> {
                saveCurrentContext();
            })
            .setNegativeButton("关闭", null)
            .setNeutralButton("复制", (dialog, which) -> {
                copyToClipboard("Context信息", fullInfo);
            })
            .create().show();
    }

    /**
     * 构建 Context 信息
     */
    private String buildContextInfo() {
        StringBuilder sb = new StringBuilder();

        sb.append("Context类型: ").append(context.getClass().getName()).append("\n");
        sb.append("PackageName: ").append(context.getPackageName()).append("\n");

        try {
            // 获取 Application
            android.app.Application app = (android.app.Application) context.getApplicationContext();
            sb.append("Application: ").append(app.getClass().getName()).append("\n");

            // 获取主线程
            Object mainThread = getMainThread();
            if (mainThread != null) {
                sb.append("主线程: ").append(mainThread.getClass().getName()).append("\n");
            }
        } catch (Exception e) {
            sb.append("获取信息失败: ").append(e.getMessage()).append("\n");
        }

        // 检查是否有 Activity
        if (context instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) context;
            sb.append("\n当前Activity: ").append(activity.getClass().getName()).append("\n");
            sb.append("TaskId: ").append(activity.getTaskId()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 获取 Activity 栈信息
     */
    private String getActivityStackInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Activity 栈 ===\n");

        try {
            // 使用反射获取 ActivityThread.mActivities
            android.app.ActivityThread activityThread = android.app.ActivityThread.currentActivityThread();
            if (activityThread != null) {
                android.util.ArrayMap<?, ?> mActivities = com.zitan.cdumpdex.ReflectUtils.getMActivities(activityThread);
                if (mActivities != null) {
                    int index = 1;
                    for (Object value : mActivities.values()) {
                        try {
                            android.app.Activity activity = com.zitan.cdumpdex.ReflectUtils.getActivity(value);
                            if (activity != null) {
                                sb.append(index++).append(". ").append(activity.getClass().getName());
                                if (activity == context) {
                                    sb.append(" [当前]");
                                }
                                sb.append("\n");
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                }
            }
        } catch (Exception e) {
            sb.append("获取失败: ").append(e.getMessage());
        }

        return sb.toString();
    }

    /**
     * 获取主线程
     */
    private Object getMainThread() {
        try {
            return android.app.ActivityThread.currentActivityThread();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 保存当前 Context 到变量
     */
    private void saveCurrentContext() {
        // 检查是否已保存
        List<String> varNames = variableManager.getVariableNames();
        for (String name : varNames) {
            RetraceableVar var = variableManager.getVariable(name);
            if (var != null && var.getValue() == context) {
                Toast.makeText(context, "当前 Context 已保存为: " + name, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        RetraceableVar var = new RetraceableVar(
            null,
            context,
            RetraceableVar.VarSource.CONTEXT
        );

        String varName = variableManager.addVariable(var);
        Toast.makeText(context, "已保存为: " + varName, Toast.LENGTH_SHORT).show();
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
     * 清理所有资源
     */
    public void cleanup() {
        // 清理无效变量
        variableManager.cleanupInvalidVariables();
    }

    // ==================== 静态方法 ====================

    /**
     * 快速显示反射菜单（静态方法）
     */
    public static void show(Context context, ClassLoader classLoader) {
        new ReflectionMenu(context, classLoader).show();
    }
}
