package com.zitan.cdumpdex.reflection.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 页面基类视图
 */
public abstract class BasePageView extends FrameLayout implements Page {

    protected final Context context;
    protected final NavigationStack navigationStack;

    // 颜色主题
    protected static final int COLOR_PRIMARY = 0xFF2196F3;
    protected static final int COLOR_PRIMARY_DARK = 0xFF1976D2;
    protected static final int COLOR_ACCENT = 0xFF03A9F4;
    protected static final int COLOR_BACKGROUND = 0xFFF5F5F5;
    protected static final int COLOR_CARD = 0xFFFFFFFF;
    protected static final int COLOR_TEXT_PRIMARY = 0xFF212121;
    protected static final int COLOR_TEXT_SECONDARY = 0xFF757575;
    protected static final int COLOR_DIVIDER = 0xFFE0E0E0;

    protected LinearLayout rootLayout;
    protected LinearLayout headerLayout;
    protected TextView titleText;
    protected LinearLayout contentLayout;
    protected ScrollView scrollView;

    protected final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public BasePageView(Context context, NavigationStack navigationStack) {
        super(context);
        this.context = context;
        this.navigationStack = navigationStack;
        initLayout();
    }

    @Override
    public View getView() {
        return this;
    }

    private void initLayout() {
        rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(COLOR_BACKGROUND);

        // 重要：设置焦点行为以支持输入法
        rootLayout.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        rootLayout.setFocusableInTouchMode(true);

        addView(rootLayout, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 头部
        headerLayout = new LinearLayout(context);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(Gravity.CENTER_VERTICAL);
        headerLayout.setBackgroundColor(COLOR_PRIMARY);
        headerLayout.setPadding(dp(4), dp(8), dp(12), dp(8));

        ImageView backButton = new ImageView(context);
        backButton.setImageResource(android.R.drawable.ic_menu_revert);
        backButton.setColorFilter(Color.WHITE);
        backButton.setPadding(dp(12), dp(8), dp(12), dp(8));
        backButton.setBackgroundResource(getSelectableBackground());
        backButton.setOnClickListener(v -> {
            if (canGoBack() && navigationStack != null) {
                navigationStack.goBack();
            }
        });
        headerLayout.addView(backButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        titleText = new TextView(context);
        titleText.setText(getTitle());
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(18);
        titleText.setTypeface(null, Typeface.BOLD);
        titleText.setGravity(Gravity.CENTER_VERTICAL);
        titleText.setSingleLine(true);
        titleText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        );
        titleParams.setMarginStart(dp(8));
        headerLayout.addView(titleText, titleParams);

        rootLayout.addView(headerLayout, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // 内容
        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BACKGROUND);
        // 重要：允许 ScrollView 内的 EditText 获取焦点
        scrollView.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);

        contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(dp(12), dp(12), dp(12), dp(12));
        // 重要：允许内容布局获取焦点
        contentLayout.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        contentLayout.setFocusableInTouchMode(true);

        scrollView.addView(contentLayout, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        rootLayout.addView(scrollView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0, 1
        ));

        initContent(contentLayout);
    }

    protected abstract void initContent(LinearLayout contentLayout);

    protected void setTitle(String title) {
        titleText.setText(title);
    }

    protected int dp(int dp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()
        );
    }

    protected int getSelectableBackground() {
        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        return outValue.resourceId;
    }

    protected int getSelectableBackgroundBorderless() {
        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true);
        return outValue.resourceId;
    }

    protected LinearLayout createCard() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(COLOR_CARD);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setElevation(dp(2));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(params);

        return card;
    }

    protected TextView createSectionTitle(String text) {
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextSize(14);
        title.setTextColor(COLOR_PRIMARY);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(16), 0, dp(8));
        return title;
    }

    protected TextView createSelectableText(String text, int textSize, int textColor) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(textSize);
        tv.setTextColor(textColor);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(4), dp(4), dp(4), dp(4));
        return tv;
    }

    protected TextView createPrimaryText(String text) {
        return createSelectableText(text, 16, COLOR_TEXT_PRIMARY);
    }

    protected TextView createSecondaryText(String text) {
        return createSelectableText(text, 13, COLOR_TEXT_SECONDARY);
    }

    protected TextView createInfoText(String text) {
        return createSelectableText(text, 14, COLOR_TEXT_SECONDARY);
    }

    protected View createDivider() {
        View divider = new View(context);
        divider.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1
        );
        params.setMargins(0, dp(8), 0, dp(8));
        divider.setLayoutParams(params);
        return divider;
    }

    protected View createMenuItem(String text, OnClickListener listener) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackgroundColor(COLOR_CARD);
        item.setPadding(dp(16), dp(16), dp(16), dp(16));
        item.setBackgroundResource(getSelectableBackground());

        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(16);
        textView.setTextColor(COLOR_TEXT_PRIMARY);
        textView.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        ));
        item.addView(textView);

        TextView arrow = new TextView(context);
        arrow.setText(">");
        arrow.setTextSize(16);
        arrow.setTextColor(COLOR_TEXT_SECONDARY);
        item.addView(arrow);

        item.setOnClickListener(listener);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(1));
        item.setLayoutParams(params);

        return item;
    }

    /**
     * 创建列表项（带标题和副标题）
     */
    protected View createListItem(String title, String subtitle, OnClickListener clickListener) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setBackgroundColor(COLOR_CARD);
        item.setPadding(dp(16), dp(12), dp(16), dp(12));
        item.setBackgroundResource(getSelectableBackground());

        TextView titleView = createSelectableText(title, 15, COLOR_TEXT_PRIMARY);
        item.addView(titleView);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subtitleView = createSelectableText(subtitle, 12, COLOR_TEXT_SECONDARY);
            subtitleView.setPadding(0, dp(4), 0, 0);
            item.addView(subtitleView);
        }

        if (clickListener != null) {
            item.setOnClickListener(clickListener);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(1));
        item.setLayoutParams(params);

        return item;
    }

    protected void showToast(String message) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    protected void copyToClipboard(String label, String text) {
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        showToast("已复制");
    }

    /**
     * 显示输入法（强制显示）
     */
    protected void showSoftInput(View view) {
        view.requestFocus();
        view.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(view, InputMethodManager.SHOW_FORCED);
            }
        }, 100);
    }

    /**
     * 隐藏输入法
     */
    protected void hideSoftInput(View view) {
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }
}
