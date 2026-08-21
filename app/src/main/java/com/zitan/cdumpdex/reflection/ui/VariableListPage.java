package com.zitan.cdumpdex.reflection.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zitan.cdumpdex.RetraceableVar;
import com.zitan.cdumpdex.reflection.VariableManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 变量列表页面
 */
public class VariableListPage extends BasePageView {

    private final ClassLoader classLoader;
    private VariableManager variableManager;
    private final boolean selectMode;

    private OnVariableSelectedListener onVariableSelectedListener;
    private LinearLayout listContainer;
    private TextView emptyText;

    public VariableListPage(Context context, NavigationStack navigationStack, ClassLoader classLoader) {
        this(context, navigationStack, classLoader, false);
    }

    public VariableListPage(Context context, NavigationStack navigationStack, ClassLoader classLoader, boolean selectMode) {
        super(context, navigationStack);
        this.classLoader = classLoader;
        try {
            this.variableManager = VariableManager.getInstance(context);
        } catch (Exception e) {
            android.util.Log.e("VariableListPage", "Failed to get VariableManager", e);
        }
        this.selectMode = selectMode;
    }

    @Override
    public String getTitle() {
        return selectMode ? "选择变量" : "保存的变量";
    }

    @Override
    protected void initContent(LinearLayout contentLayout) {
        if (!selectMode) {
            LinearLayout manageRow = new LinearLayout(context);
            manageRow.setOrientation(LinearLayout.HORIZONTAL);
            manageRow.setGravity(Gravity.END);
            manageRow.setPadding(0, 0, 0, dp(8));

            Button cleanupBtn = createTextButton("清理无效", v -> cleanupInvalidVariables());
            manageRow.addView(cleanupBtn);

            Button clearBtn = createTextButton("清空全部", v -> clearAllVariables());
            manageRow.addView(clearBtn);

            contentLayout.addView(manageRow);
        }

        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        contentLayout.addView(listContainer);

        emptyText = new TextView(context);
        emptyText.setText("暂无保存的变量");
        emptyText.setTextColor(COLOR_TEXT_SECONDARY);
        emptyText.setPadding(0, dp(32), 0, 0);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setVisibility(GONE);
        contentLayout.addView(emptyText);
    }

    private Button createTextButton(String text, OnClickListener listener) {
        Button btn = new Button(context, null, android.R.attr.borderlessButtonStyle);
        btn.setText(text);
        btn.setTextSize(13);
        btn.setTextColor(COLOR_PRIMARY);
        btn.setOnClickListener(listener);
        return btn;
    }

    @Override
    public void onShow() {
        refreshList();
    }

    private void refreshList() {
        listContainer.removeAllViews();

        if (variableManager == null) {
            emptyText.setText("变量管理器未初始化");
            emptyText.setVisibility(VISIBLE);
            return;
        }

        Map<String, RetraceableVar> variables = variableManager.getAllVariables();

        if (variables.isEmpty()) {
            emptyText.setText("暂无保存的变量");
            emptyText.setVisibility(VISIBLE);
            return;
        }

        emptyText.setVisibility(GONE);

        for (Map.Entry<String, RetraceableVar> entry : variables.entrySet()) {
            View item = createVariableItem(entry.getKey(), entry.getValue());
            listContainer.addView(item);
        }
    }

    private View createVariableItem(String varName, RetraceableVar var) {
        // 检查值是否有效
        boolean isValid = var.isValueValid() || var.getSource() == RetraceableVar.VarSource.PRIMITIVE;
        Object value = var.getValue();
        boolean hasValue = value != null;

        LinearLayout card = createCard();
        card.setOrientation(LinearLayout.VERTICAL);

        // 第一行：变量名 + 来源标签
        LinearLayout row1 = new LinearLayout(context);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView nameView = createSelectableText(varName, 16, COLOR_TEXT_PRIMARY);
        nameView.setTypeface(null, Typeface.BOLD);
        nameView.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        ));
        row1.addView(nameView);

        TextView sourceTag = new TextView(context);
        sourceTag.setText(getSourceLabel(var.getSource()));
        sourceTag.setTextSize(11);
        sourceTag.setTextColor(Color.WHITE);
        sourceTag.setBackgroundColor(getSourceColor(var.getSource()));
        sourceTag.setPadding(dp(6), dp(2), dp(6), dp(2));
        row1.addView(sourceTag);

        card.addView(row1);

        // 第二行：类型
        TextView typeView = createSelectableText(var.getTypeDisplayName(), 13, COLOR_TEXT_SECONDARY);
        typeView.setPadding(0, dp(4), 0, 0);
        card.addView(typeView);

        // 第三行：值预览
        String valuePreview;
        int valueColor;
        if (!isValid) {
            valuePreview = "(值已被GC回收)";
            valueColor = 0xFFE53935;
        } else if (!hasValue) {
            valuePreview = "null";
            valueColor = COLOR_TEXT_SECONDARY;
        } else {
            valuePreview = var.getValueDisplayString();
            if (valuePreview.length() > 80) {
                valuePreview = valuePreview.substring(0, 80) + "...";
            }
            valueColor = COLOR_TEXT_SECONDARY;
        }

        TextView valueView = new TextView(context);
        valueView.setText(valuePreview);
        valueView.setTextSize(12);
        valueView.setTextColor(valueColor);
        valueView.setTextIsSelectable(true);
        valueView.setPadding(0, dp(2), 0, 0);
        card.addView(valueView);

        // 点击卡片查看详情或类结构
        card.setBackgroundResource(getSelectableBackground());
        card.setOnClickListener(v -> {
            if (hasValue && isValid) {
                // 有值：跳转到变量详情页面，显示字段和方法
                VariableDetailPage page = new VariableDetailPage(context, navigationStack, classLoader, value, varName);
                navigationStack.push(page);
            } else {
                // 无值：尝试查看类结构
                Class<?> type = var.getType();
                if (type != null) {
                    ClassViewPage page = new ClassViewPage(context, navigationStack, classLoader, type);
                    navigationStack.push(page);
                } else {
                    showToast("无法获取类型信息");
                }
            }
        });

        // 选择模式：覆盖点击行为
        if (selectMode) {
            card.setOnClickListener(v -> {
                if (onVariableSelectedListener != null) {
                    onVariableSelectedListener.onVariableSelected(varName, var);
                    navigationStack.goBack();
                }
            });
        }

        // 删除按钮（非选择模式）
        if (!selectMode) {
            LinearLayout btnRow = new LinearLayout(context);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.END);
            btnRow.setPadding(0, dp(12), 0, 0);

            Button deleteBtn = new Button(context);
            deleteBtn.setText("删除");
            deleteBtn.setTextColor(0xFFE53935);
            deleteBtn.setOnClickListener(v -> {
                variableManager.removeVariable(varName);
                refreshList();
                showToast("已删除: " + varName);
            });
            btnRow.addView(deleteBtn);

            card.addView(btnRow);
        }

        return card;
    }

    private int getSourceColor(RetraceableVar.VarSource source) {
        switch (source) {
            case CONTEXT: return 0xFF4CAF50;
            case CONSTRUCTOR: return 0xFF2196F3;
            case METHOD_RETURN: return 0xFFFF9800;
            case INSTANCE_SCAN: return 0xFF9C27B0;
            case FIELD_ACCESS:
            case STATIC_FIELD: return 0xFF00BCD4;
            case PRIMITIVE: return 0xFF607D8B;
            default: return 0xFF888888;
        }
    }

    private String getSourceLabel(RetraceableVar.VarSource source) {
        switch (source) {
            case CONTEXT: return "Context";
            case CONSTRUCTOR: return "创建";
            case METHOD_RETURN: return "方法";
            case INSTANCE_SCAN: return "扫描";
            case FIELD_ACCESS:
            case STATIC_FIELD: return "字段";
            case PRIMITIVE: return "基本";
            default: return "其他";
        }
    }

    private void cleanupInvalidVariables() {
        if (variableManager == null) return;

        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, RetraceableVar> entry : variableManager.getAllVariables().entrySet()) {
            RetraceableVar var = entry.getValue();
            if (!var.isValueValid() && var.getSource() != RetraceableVar.VarSource.PRIMITIVE) {
                toRemove.add(entry.getKey());
            }
        }

        if (toRemove.isEmpty()) {
            showToast("没有无效变量");
            return;
        }

        for (String name : toRemove) {
            variableManager.removeVariable(name);
        }
        refreshList();
        showToast("已清理 " + toRemove.size() + " 个无效变量");
    }

    private void clearAllVariables() {
        if (variableManager == null) return;

        new android.app.AlertDialog.Builder(context)
            .setTitle("确认清空")
            .setMessage("确定要清空所有变量吗？")
            .setPositiveButton("清空", (dialog, which) -> {
                variableManager.clearAll();
                refreshList();
                showToast("已清空所有变量");
            })
            .setNegativeButton("取消", null)
            .create().show();
    }

    public void setOnVariableSelectedListener(OnVariableSelectedListener listener) {
        this.onVariableSelectedListener = listener;
    }

    public interface OnVariableSelectedListener {
        void onVariableSelected(String varName, RetraceableVar var);
    }
}
