package com.zitan.cdumpdex.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.zitan.cdumpdex.MainActivity;
import com.zitan.cdumpdex.util.ConfigManager;
import com.zitan.cdumpdex.util.ProcessListHelper;
import com.zitan.cdumpdex.util.ProcessListHelper.ProcessEntry;
import com.zitan.cdumpdex.util.RootMemoryScanner;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 悬浮窗服务
 *
 * 在屏幕上显示一个可拖动的悬浮图标（类似 GG 修改器），
 * 点击后弹出进程列表悬浮窗，选择进程后执行 Root 内存脱壳。
 */
public class FloatingWindowService extends Service {
    private static final String TAG = "FloatingWindowService";
    private static final String CHANNEL_ID = "floating_window_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int CLICK_THRESHOLD = 10;
    private static final int OVERLAY_TOAST_DURATION = 3500;

    // 脱壳输出基础目录
    private static final String DUMP_BASE_DIR = "/storage/emulated/0/cDumpDex/dump";

    private WindowManager windowManager;
    private View floatingView;
    private View processListView;
    private View overlayToastView;
    private WindowManager.LayoutParams floatParams;
    private WindowManager.LayoutParams listParams;
    private float initialX, initialY;
    private float initialTouchX, initialTouchY;
    private Handler mainHandler;
    private PackageManager pm;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        pm = getPackageManager();
        startForegroundNotification();
        createFloatingView();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (floatingView != null && floatingView.getParent() == null) {
            try {
                windowManager.addView(floatingView, floatParams);
                Log.d(TAG, "Floating view added");
            } catch (Exception e) {
                Log.e(TAG, "Failed to add floating view", e);
                stopSelf();
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeProcessListView();
        removeOverlayToast();
        if (floatingView != null && floatingView.getParent() != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove floating view", e);
            }
        }
        Log.d(TAG, "FloatingWindowService destroyed");
    }

    // ============================================================
    // 前台通知
    // ============================================================

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "cDumpDex悬浮窗",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Root内存脱壳悬浮窗服务");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("cDumpDex")
                    .setContentText("Root内存脱壳悬浮窗运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle("cDumpDex")
                    .setContentText("Root内存脱壳悬浮窗运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_LOW)
                    .build();
        }

        startForeground(NOTIFICATION_ID, notification);
    }

    // ============================================================
    // 悬浮图标
    // ============================================================

    private void createFloatingView() {
        float density = getResources().getDisplayMetrics().density;

        floatingView = new View(this) {
            private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final float d = getResources().getDisplayMetrics().density;

            {
                bgPaint.setColor(0xCC2196F3);
                bgPaint.setStyle(Paint.Style.FILL);
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(18 * d);
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setFakeBoldText(true);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth();
                int h = getHeight();
                float cx = w / 2f;
                float cy = h / 2f;
                float radius = Math.min(cx, cy) - 4 * d;

                canvas.drawCircle(cx, cy, radius, bgPaint);

                Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                borderPaint.setStyle(Paint.Style.STROKE);
                borderPaint.setColor(0xFFFFFFFF);
                borderPaint.setStrokeWidth(2 * d);
                canvas.drawCircle(cx, cy, radius, borderPaint);

                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float textY = cy - (fm.ascent + fm.descent) / 2f;
                canvas.drawText("D", cx, textY, textPaint);
            }
        };

        int size = (int) (48 * density);
        floatingView.setLayoutParams(new WindowManager.LayoutParams(size, size));

        floatingView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = floatParams.x;
                    initialY = floatParams.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    floatParams.x = (int) (initialX + event.getRawX() - initialTouchX);
                    floatParams.y = (int) (initialY + event.getRawY() - initialTouchY);
                    try {
                        windowManager.updateViewLayout(floatingView, floatParams);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to update floating view position", e);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = Math.abs(event.getRawX() - initialTouchX);
                    float dy = Math.abs(event.getRawY() - initialTouchY);
                    if (dx < CLICK_THRESHOLD && dy < CLICK_THRESHOLD) {
                        onFloatingViewClicked();
                    }
                    return true;
            }
            return false;
        });

        int layoutType = getOverlayType();

        floatParams = new WindowManager.LayoutParams(
                size, size,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        floatParams.gravity = Gravity.TOP | Gravity.START;
        floatParams.x = 0;
        floatParams.y = 200;
    }

    // ============================================================
    // 进程列表悬浮窗
    // ============================================================

    private int getOverlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int dp(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }

    private void onFloatingViewClicked() {
        Log.d(TAG, "Floating view clicked, showing process list");
        removeProcessListView();
        showOverlayToast("正在获取进程列表...");

        new Thread(() -> {
            List<ProcessEntry> processes = ProcessListHelper.getAppProcesses();
            // 解析应用名称
            Map<String, String> appNameMap = resolveAppNames(processes);

            mainHandler.post(() -> {
                if (processes.isEmpty()) {
                    showOverlayToast("未找到运行中的应用进程，请检查Root权限");
                    return;
                }
                showProcessListOverlay(processes, appNameMap);
            });
        }).start();
    }

    /**
     * 解析进程名对应的应用显示名称
     */
    private Map<String, String> resolveAppNames(List<ProcessEntry> processes) {
        Map<String, String> nameMap = new HashMap<>();
        for (ProcessEntry p : processes) {
            // 提取基础包名（去掉 :xxx 子进程后缀）
            String baseName = p.name;
            int colonIdx = baseName.indexOf(':');
            if (colonIdx > 0) {
                baseName = baseName.substring(0, colonIdx);
            }
            // 只解析包含 '.' 的（看起来像包名的）
            if (baseName.contains(".")) {
                try {
                    android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(baseName, 0);
                    CharSequence label = pm.getApplicationLabel(ai);
                    if (label != null && label.length() > 0) {
                        nameMap.put(p.name, label.toString());
                    }
                } catch (PackageManager.NameNotFoundException ignored) {}
            }
        }
        return nameMap;
    }

    /**
     * 通过 WindowManager 叠加层显示进程列表
     */
    private void showProcessListOverlay(List<ProcessEntry> processes, Map<String, String> appNameMap) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int dialogWidth = Math.min(screenWidth - dp(32), dp(420));

        // --- 根布局 ---
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F5F5);

        // --- 标题栏 ---
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setBackgroundColor(0xFF1976D2);
        titleBar.setPadding(dp(16), dp(12), dp(8), dp(12));

        TextView titleText = new TextView(this);
        titleText.setText("运行中的应用进程 (" + processes.size() + ")");
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(16);
        titleText.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView closeBtn = new TextView(this);
        closeBtn.setText("\u2715");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setTextSize(20);
        closeBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
        closeBtn.setOnClickListener(v -> removeProcessListView());

        titleBar.addView(titleText);
        titleBar.addView(closeBtn);

        // --- 搜索栏 ---
        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackgroundColor(Color.WHITE);
        searchBar.setPadding(dp(8), dp(4), dp(8), dp(4));

        EditText searchInput = new EditText(this);
        searchInput.setHint("搜索应用名或进程名...");
        searchInput.setTextSize(14);
        searchInput.setTextColor(0xFF212121);
        searchInput.setHintTextColor(0xFFAAAAAA);
        searchInput.setBackgroundColor(0x00000000);
        searchInput.setSingleLine(true);
        searchInput.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        searchInput.setLayoutParams(inputParams);

        // 清除按钮（一键清除搜索框）
        TextView clearBtn = new TextView(this);
        clearBtn.setText("\u2715");
        clearBtn.setTextColor(0xFF757575);
        clearBtn.setTextSize(16);
        clearBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        clearBtn.setVisibility(View.GONE); // 默认隐藏
        clearBtn.setOnClickListener(v -> {
            searchInput.setText("");
        });

        searchBar.addView(searchInput);
        searchBar.addView(clearBtn);

        // 底部分隔线
        View divider = new View(this);
        divider.setBackgroundColor(0xFFE0E0E0);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // --- 列表 ---
        ListView listView = new ListView(this);
        listView.setBackgroundColor(Color.WHITE);
        listView.setDividerHeight(1);

        ProcessListAdapter adapter = new ProcessListAdapter(processes, appNameMap);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            ProcessEntry selected = adapter.getFilteredItem(position);
            if (selected == null) return;
            String appName = appNameMap.get(selected.name);
            removeProcessListView();
            executeDump(selected, appName);
        });

        int maxListHeight = dp(360);
        int itemHeight = dp(58);
        int listHeight = Math.min(processes.size() * itemHeight, maxListHeight);
        listView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listHeight));

        // 搜索文本变化监听
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase();
                adapter.filter(query);
                // 显示/隐藏清除按钮
                clearBtn.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                // 更新标题中的数量
                titleText.setText("运行中的应用进程 (" + adapter.getCount() + "/" + processes.size() + ")");
                // 调整列表高度
                int h = Math.min(adapter.getCount() * itemHeight, maxListHeight);
                if (h < dp(100)) h = dp(100);
                listView.getLayoutParams().height = h;
                listView.requestLayout();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        root.addView(titleBar);
        root.addView(searchBar);
        root.addView(divider);
        root.addView(listView);

        // --- 添加到 WindowManager ---
        int layoutType = getOverlayType();

        listParams = new WindowManager.LayoutParams(
                dialogWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        listParams.gravity = Gravity.CENTER;

        // 处理外部触摸关闭
        processListView = new LinearLayout(this) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    removeProcessListView();
                    return true;
                }
                return super.onTouchEvent(event);
            }
        };
        ((LinearLayout) processListView).setOrientation(LinearLayout.VERTICAL);
        ((LinearLayout) processListView).setBackgroundColor(0xFFF5F5F5);
        ((LinearLayout) processListView).addView(root);

        try {
            windowManager.addView(processListView, listParams);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show process list overlay", e);
            showOverlayToast("无法显示进程列表: " + e.getMessage());
        }
    }

    private void removeProcessListView() {
        if (processListView != null) {
            try {
                if (processListView.getParent() != null) {
                    windowManager.removeView(processListView);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove process list view", e);
            }
            processListView = null;
        }
    }

    // ============================================================
    // 叠加层 Toast（代替系统 Toast，在任何应用上层可见）
    // ============================================================

    private void showOverlayToastInMain(String msg) {
        mainHandler.post(() -> showOverlayToast(msg));
    }

    private void showOverlayToast(String msg) {
        // 先移除旧的
        removeOverlayToast();

        float density = getResources().getDisplayMetrics().density;

        TextView toastView = new TextView(this);
        toastView.setText(msg);
        toastView.setTextColor(Color.WHITE);
        toastView.setTextSize(14);
        toastView.setGravity(Gravity.CENTER);
        toastView.setBackgroundColor(0xDD424242);
        toastView.setPadding(dp(16), dp(12), dp(16), dp(12));

        int layoutType = getOverlayType();
        WindowManager.LayoutParams toastParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        toastParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        toastParams.y = dp(80);

        overlayToastView = toastView;

        try {
            windowManager.addView(overlayToastView, toastParams);
            // 自动消失
            mainHandler.postDelayed(this::removeOverlayToast, OVERLAY_TOAST_DURATION);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show overlay toast", e);
        }
    }

    private void removeOverlayToast() {
        if (overlayToastView != null) {
            try {
                if (overlayToastView.getParent() != null) {
                    windowManager.removeView(overlayToastView);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove overlay toast", e);
            }
            overlayToastView = null;
        }
    }

    private void executeDump(ProcessEntry entry, String appName) {
        String displayName = appName != null ? appName : entry.name;

        new Thread(() -> {
            try {
                File outputDir = new File(DUMP_BASE_DIR,
                        "root_memory_" + System.currentTimeMillis());
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    Log.w(TAG, "Failed to create output dir: " + outputDir.getAbsolutePath());
                }

                showOverlayToastInMain("正在dump: " + displayName + " ...");

                RootMemoryScanner scanner = new RootMemoryScanner(this);
                // Root 模式只扫描内存，不跨进程注入或主动调用类。
                int count = scanner.dumpDexFromPid(entry.pid, outputDir);

                final String msg;
                if (count >= 0) {
                    msg = "脱壳完成: " + displayName + "\n找到 " + count + " 个DEX\n"
                            + "保存在: " + outputDir.getAbsolutePath();
                } else {
                    msg = "脱壳失败: " + displayName + " (错误码: " + count + ")";
                }
                Log.d(TAG, "Dump result for " + entry.name + ": " + count + " dex, output: " + outputDir.getAbsolutePath());
                mainHandler.post(() -> showOverlayToast(msg));
            } catch (Exception e) {
                Log.e(TAG, "Dump failed for " + entry.name, e);
                mainHandler.post(() -> showOverlayToast("脱壳失败: " + e.getMessage()));
            }
        }).start();
    }

    private class ProcessListAdapter extends BaseAdapter {
        private final List<ProcessEntry> allProcesses;
        private final List<ProcessEntry> filteredProcesses;
        private final Map<String, String> appNameMap;

        ProcessListAdapter(List<ProcessEntry> processes, Map<String, String> appNameMap) {
            this.allProcesses = processes;
            this.filteredProcesses = new ArrayList<>(processes);
            this.appNameMap = appNameMap;
        }

        void filter(String query) {
            filteredProcesses.clear();
            if (query.isEmpty()) {
                filteredProcesses.addAll(allProcesses);
            } else {
                for (ProcessEntry entry : allProcesses) {
                    String appName = appNameMap.get(entry.name);
                    // 同时匹配应用名和进程名
                    boolean matchApp = appName != null && appName.toLowerCase().contains(query);
                    boolean matchProc = entry.name.toLowerCase().contains(query);
                    if (matchApp || matchProc) {
                        filteredProcesses.add(entry);
                    }
                }
            }
            notifyDataSetChanged();
        }

        ProcessEntry getFilteredItem(int position) {
            if (position >= 0 && position < filteredProcesses.size()) {
                return filteredProcesses.get(position);
            }
            return null;
        }

        @Override
        public int getCount() {
            return filteredProcesses.size();
        }

        @Override
        public Object getItem(int position) {
            return filteredProcesses.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout item;
            if (convertView instanceof LinearLayout) {
                item = (LinearLayout) convertView;
            } else {
                item = new LinearLayout(FloatingWindowService.this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(dp(16), dp(8), dp(16), dp(8));
                item.setBackgroundResource(android.R.drawable.list_selector_background);
            }

            item.removeAllViews();

            ProcessEntry entry = filteredProcesses.get(position);
            String appName = appNameMap.get(entry.name);

            // 第一行：应用名（粗体）
            TextView nameLine = new TextView(FloatingWindowService.this);
            String displayName = appName != null ? appName : entry.name;
            nameLine.setText(displayName);
            nameLine.setTextSize(15);
            nameLine.setTextColor(0xFF212121);
            nameLine.getPaint().setFakeBoldText(true);
            item.addView(nameLine);

            // 第二行：进程名 + PID（小字灰色）
            TextView detailLine = new TextView(FloatingWindowService.this);
            StringBuilder detail = new StringBuilder();
            if (appName != null && !appName.equals(entry.name)) {
                detail.append(entry.name);
            } else if (appName == null) {
                detail.append(entry.name);
            }
            if (detail.length() > 0) {
                detail.append("  ");
            }
            detail.append("PID: ").append(entry.pid);
            detailLine.setText(detail.toString());
            detailLine.setTextSize(12);
            detailLine.setTextColor(0xFF757575);
            item.addView(detailLine);

            return item;
        }
    }

    // ============================================================
    // 静态方法
    // ============================================================

    public static void start(Context context) {
        Intent intent = new Intent(context, FloatingWindowService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, FloatingWindowService.class);
        context.stopService(intent);
    }

    public static boolean isRunning(Context context) {
        android.app.ActivityManager manager = (android.app.ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (android.app.ActivityManager.RunningServiceInfo service :
                    manager.getRunningServices(Integer.MAX_VALUE)) {
                if (FloatingWindowService.class.getName().equals(
                        service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
