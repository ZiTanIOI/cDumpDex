package com.zitan.cdumpdex.reflection;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.zitan.cdumpdex.R;
import com.zitan.cdumpdex.RetraceableVar;

import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 变量列表对话框
 * 显示所有保存的变量，支持查看、删除等操作
 */
public class VariableListDialog {

    private final Context context;
    private final VariableManager variableManager;
    private final ClassLoader classLoader;
    private ClassStructureViewer classStructureViewer;

    public VariableListDialog(Context context, ClassLoader classLoader) {
        this.context = context;
        this.classLoader = classLoader;
        this.variableManager = VariableManager.getInstance(context);
    }

    /**
     * 设置类结构查看器
     */
    public void setClassStructureViewer(ClassStructureViewer viewer) {
        this.classStructureViewer = viewer;
    }

    /**
     * 显示变量列表对话框
     */
    public void show() {
        show(null);
    }

    /**
     * 显示变量列表对话框，带选择回调
     * @param onVariableSelected 变量选择回调（用于选择变量作为方法参数等）
     */
    public void show(OnVariableSelectedListener onVariableSelected) {
        Map<String, RetraceableVar> variables = variableManager.getAllVariables();

        if (variables.isEmpty()) {
            new AlertDialog.Builder(context)
                .setTitle("保存的变量")
                .setMessage("暂无保存的变量")
                .setPositiveButton("确定", null)
                .create().show();
            return;
        }

        List<VarItem> items = new ArrayList<>();
        for (Map.Entry<String, RetraceableVar> entry : variables.entrySet()) {
            items.add(new VarItem(entry.getKey(), entry.getValue()));
        }

        VarAdapter adapter = new VarAdapter(context, items);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("保存的变量 (" + items.size() + ")");

        // 如果是选择模式，使用单选列表
        if (onVariableSelected != null) {
            builder.setAdapter(adapter, (dialog, which) -> {
                VarItem item = items.get(which);
                onVariableSelected.onVariableSelected(item.name, item.var);
            });
        } else {
            // 否则使用可点击的列表，点击后显示详情
            builder.setAdapter(adapter, (dialog, which) -> {
                VarItem item = items.get(which);
                showVariableDetail(item.name, item.var);
            });
        }

        // 添加清理菜单
        builder.setNeutralButton("管理", (dialog, which) -> {
            showManageMenu(items);
        });

        builder.setNegativeButton("关闭", null);
        builder.create().show();
    }

    /**
     * 显示变量详情
     */
    private void showVariableDetail(String name, RetraceableVar var) {
        Object value = var.getValue();

        if (value == null) {
            // 值已被 GC
            showInvalidVariableDialog(name, var);
            return;
        }

        Class<?> clazz = var.getType();
        if (clazz == null) {
            clazz = value.getClass();
        }

        if (classStructureViewer != null) {
            classStructureViewer.show(value, clazz, name, this::onMethodSelected);
        } else {
            // 简单显示
            showSimpleDetail(name, var, value);
        }
    }

    /**
     * 显示无效变量对话框
     */
    private void showInvalidVariableDialog(String name, RetraceableVar var) {
        new AlertDialog.Builder(context)
            .setTitle("变量: " + name)
            .setMessage("该变量的值已被垃圾回收\n来源: " + var.getSource().name())
            .setPositiveButton("删除", (dialog, which) -> {
                variableManager.removeVariable(name);
                Toast.makeText(context, "已删除: " + name, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .create().show();
    }

    /**
     * 简单详情显示（无类结构查看器时）
     */
    private void showSimpleDetail(String name, RetraceableVar var, Object value) {
        StringBuilder sb = new StringBuilder();
        sb.append("名称: ").append(name).append("\n");
        sb.append("类型: ").append(value.getClass().getName()).append("\n");
        sb.append("来源: ").append(var.getSource().name()).append("\n");
        sb.append("值: ").append(var.getValueDisplayString());

        new AlertDialog.Builder(context)
            .setTitle("变量详情")
            .setMessage(sb.toString())
            .setPositiveButton("确定", null)
            .create().show();
    }

    /**
     * 方法被选中回调
     */
    private void onMethodSelected(java.lang.reflect.Method method, Object instance, String varName) {
        // 打开方法调用器
        MethodInvoker methodInvoker = new MethodInvoker(context, classLoader);
        methodInvoker.show(method, instance, varName);
    }

    /**
     * 显示管理菜单
     */
    private void showManageMenu(List<VarItem> items) {
        CharSequence[] options = {
            "清理无效变量",
            "清空所有变量",
            "导出变量配置",
            "重新加载变量配置"
        };

        new AlertDialog.Builder(context)
            .setTitle("变量管理")
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0:
                        cleanupInvalidVariables(items);
                        break;
                    case 1:
                        clearAllVariables();
                        break;
                    case 2:
                        exportVariables();
                        break;
                    case 3:
                        reloadVariables();
                        break;
                }
            })
            .setNegativeButton("取消", null)
            .create().show();
    }

    /**
     * 清理无效变量
     */
    private void cleanupInvalidVariables(List<VarItem> items) {
        List<String> toRemove = new ArrayList<>();
        for (VarItem item : items) {
            if (!item.var.isValueValid() && item.var.getSource() != RetraceableVar.VarSource.PRIMITIVE) {
                toRemove.add(item.name);
            }
        }

        if (toRemove.isEmpty()) {
            Toast.makeText(context, "没有无效变量", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(context)
            .setTitle("清理无效变量")
            .setMessage("发现 " + toRemove.size() + " 个无效变量（值已被GC）\n" + TextUtils.join("\n", toRemove))
            .setPositiveButton("删除", (dialog, which) -> {
                for (String name : toRemove) {
                    variableManager.removeVariable(name);
                }
                Toast.makeText(context, "已清理 " + toRemove.size() + " 个变量", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .create().show();
    }

    /**
     * 清空所有变量
     */
    private void clearAllVariables() {
        new AlertDialog.Builder(context)
            .setTitle("清空所有变量")
            .setMessage("确定要清空所有保存的变量吗？此操作不可恢复。")
            .setPositiveButton("清空", (dialog, which) -> {
                variableManager.clearAll();
                Toast.makeText(context, "已清空所有变量", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .create().show();
    }

    /**
     * 导出变量配置
     */
    private void exportVariables() {
        // 变量配置会自动保存到文件
        Toast.makeText(context, "变量配置已自动保存", Toast.LENGTH_SHORT).show();
    }

    /**
     * 重新加载变量配置
     */
    private void reloadVariables() {
        variableManager.loadFromFile();
        Toast.makeText(context, "已重新加载变量配置", Toast.LENGTH_SHORT).show();
    }

    // ==================== 内部类 ====================

    /**
     * 变量列表项
     */
    private static class VarItem {
        final String name;
        final RetraceableVar var;

        VarItem(String name, RetraceableVar var) {
            this.name = name;
            this.var = var;
        }
    }

    /**
     * 变量列表适配器
     */
    private static class VarAdapter extends ArrayAdapter<VarItem> {

        public VarAdapter(Context context, List<VarItem> items) {
            super(context, android.R.layout.simple_list_item_2, android.R.id.text1, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = super.getView(position, convertView, parent);

            TextView text1 = view.findViewById(android.R.id.text1);
            TextView text2 = view.findViewById(android.R.id.text2);

            VarItem item = getItem(position);
            if (item != null) {
                text1.setText(item.name);

                RetraceableVar var = item.var;
                StringBuilder sb = new StringBuilder();

                // 类型
                sb.append(var.getTypeDisplayName());

                // 来源标记
                switch (var.getSource()) {
                    case CONTEXT:
                        sb.append(" [Context]");
                        break;
                    case CONSTRUCTOR:
                        sb.append(" [创建]");
                        break;
                    case METHOD_RETURN:
                        sb.append(" [方法返回]");
                        break;
                    case INSTANCE_SCAN:
                        sb.append(" [扫描]");
                        break;
                    case FIELD_ACCESS:
                    case STATIC_FIELD:
                        sb.append(" [字段]");
                        break;
                }

                // 值状态
                if (!var.isValueValid() && var.getSource() != RetraceableVar.VarSource.PRIMITIVE) {
                    sb.append(" ⚠️已失效");
                } else {
                    String valueStr = var.getValueDisplayString();
                    if (valueStr.length() > 30) {
                        valueStr = valueStr.substring(0, 30) + "...";
                    }
                    sb.append(": ").append(valueStr);
                }

                text2.setText(sb.toString());
            }

            return view;
        }
    }

    // ==================== 接口定义 ====================

    /**
     * 变量选择监听器
     */
    public interface OnVariableSelectedListener {
        void onVariableSelected(String varName, RetraceableVar var);
    }
}
