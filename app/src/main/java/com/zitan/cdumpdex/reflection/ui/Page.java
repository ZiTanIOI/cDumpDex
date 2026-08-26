package com.zitan.cdumpdex.reflection.ui;

import android.view.View;

/**
 * 页面接口
 * 所有反射功能页面需要实现此接口
 */
public interface Page {

    /**
     * 获取页面标题
     */
    String getTitle();

    /**
     * 获取页面视图
     */
    View getView();

    /**
     * 当页面显示时调用
     */
    default void onShow() {}

    /**
     * 当页面隐藏时调用
     */
    default void onHide() {}

    /**
     * 当页面从栈中移除时调用
     */
    default void onDestroy() {}

    /**
     * 是否可以返回
     * @return true 表示可以返回，false 表示阻止返回
     */
    default boolean canGoBack() {
        return true;
    }

    /**
     * 处理返回键
     * @return true 表示已处理，false 表示交给导航栈处理
     */
    default boolean onBackPressed() {
        return false;
    }
}
