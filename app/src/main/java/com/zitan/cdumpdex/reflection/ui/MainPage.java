package com.zitan.cdumpdex.reflection.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zitan.cdumpdex.reflection.VariableManager;

import java.util.List;

/**
 * 主菜单页面
 */
public class MainPage extends BasePageView {

    private final ClassLoader classLoader;
    private final List<ClassLoader> additionalClassLoaders;
    private VariableManager variableManager;
    private TextView statsText;

    public MainPage(Context context, NavigationStack navigationStack, ClassLoader classLoader) {
        this(context, navigationStack, classLoader, null);
    }

    public MainPage(Context context, NavigationStack navigationStack, ClassLoader classLoader, List<ClassLoader> additionalClassLoaders) {
        super(context, navigationStack);
        this.classLoader = classLoader;
        this.additionalClassLoaders = additionalClassLoaders;
        try {
            this.variableManager = VariableManager.getInstance(context);
        } catch (Exception e) {
            android.util.Log.e("MainPage", "Failed to get VariableManager", e);
        }
    }

    @Override
    public String getTitle() {
        return "反射工具";
    }

    @Override
    protected void initContent(LinearLayout contentLayout) {
        // 变量统计卡片
        LinearLayout statsCard = createCard();
        statsText = new TextView(context);
        updateStatsText();
        statsText.setTextSize(14);
        statsText.setTextColor(COLOR_TEXT_SECONDARY);
        statsText.setGravity(Gravity.CENTER);
        statsCard.addView(statsText);
        contentLayout.addView(statsCard);

        // 核心功能
        contentLayout.addView(createSectionTitle("核心功能"));

        contentLayout.addView(createMenuItem("创建对象", v -> {
            navigationStack.push(new CreateObjectPage(context, navigationStack, classLoader));
        }));

        contentLayout.addView(createMenuItem("调用方法", v -> {
            navigationStack.push(new MethodInvokePage(context, navigationStack, classLoader, additionalClassLoaders, null, null));
        }));

        contentLayout.addView(createMenuItem("获取内存实例", v -> {
            navigationStack.push(new InstanceScanPage(context, navigationStack, classLoader));
        }));

        // 变量管理
        contentLayout.addView(createSectionTitle("变量管理"));

        contentLayout.addView(createMenuItem("保存的变量", v -> {
            navigationStack.push(new VariableListPage(context, navigationStack, classLoader));
        }));

        contentLayout.addView(createMenuItem("当前 Context", v -> {
            navigationStack.push(new CurrentContextPage(context, navigationStack));
        }));

        // 工具
        contentLayout.addView(createSectionTitle("工具"));

        contentLayout.addView(createMenuItem("查看类结构", v -> {
            navigationStack.push(new ClassViewPage(context, navigationStack, classLoader, additionalClassLoaders, null, null));
        }));
    }

    private void updateStatsText() {
        if (variableManager != null) {
            try {
                int varCount = variableManager.getVariableNames().size();
                statsText.setText("已保存 " + varCount + " 个变量");
            } catch (Exception e) {
                statsText.setText("变量统计不可用");
            }
        } else {
            statsText.setText("变量管理器未初始化");
        }
    }

    @Override
    public void onShow() {
        updateStatsText();
    }

    @Override
    public boolean canGoBack() {
        return false;
    }
}
