package com.zitan.cdumpdex.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import com.zitan.cdumpdex.util.ProcessListHelper;
import com.zitan.cdumpdex.util.ProcessListHelper.ProcessEntry;
import com.zitan.cdumpdex.util.RootMemoryScanner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 进程列表对话框
 * 显示当前运行的应用进程列表，点击后执行 Root 内存脱壳
 */
public class ProcessListDialog {
    private static final String TAG = "ProcessListDialog";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ProcessListDialog(Context context) {
        // 使用 ApplicationContext 避免对话框在 Service 上下文中出现问题
        this.context = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
    }

    /**
     * 显示运行中的进程列表对话框
     */
    public void show() {
        // 在后台线程获取进程列表
        new Thread(() -> {
            List<ProcessEntry> processes = ProcessListHelper.getAppProcesses();

            mainHandler.post(() -> {
                showDialog(processes);
            });
        }).start();
    }

    private void showDialog(List<ProcessEntry> processes) {
        if (processes.isEmpty()) {
            Toast.makeText(context, "未找到运行中的应用进程，请检查Root权限", Toast.LENGTH_LONG).show();
            return;
        }

        // 构建列表项
        List<String> items = new ArrayList<>();
        for (ProcessEntry entry : processes) {
            items.add(entry.toString());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context,
                android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("运行中的应用进程 (" + processes.size() + ")");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_list_item_1,
                items);

        builder.setAdapter(adapter, (dialog, which) -> {
            if (which >= 0 && which < processes.size()) {
                ProcessEntry selected = processes.get(which);
                dialog.dismiss();
                executeDump(selected);
            }
        });

        builder.setNegativeButton("关闭", (dialog, which) -> dialog.dismiss());

        try {
            builder.create().show();
        } catch (android.view.WindowManager.BadTokenException e) {
            Log.e(TAG, "Cannot show dialog: " + e.getMessage());
            // 尝试使用 TYPE_APPLICATION_OVERLAY 类型的窗口显示
            showAsOverlayDialog(processes);
        }
    }

    /**
     * 备用方案：当 Service 上下文无法直接弹出 Dialog 时，
     * 使用系统级 AlertDialog（需要 SYSTEM_ALERT_WINDOW 权限）
     */
    private void showAsOverlayDialog(List<ProcessEntry> processes) {
        // 使用 Toast 显示简化的进程列表（作为备用方案）
        StringBuilder sb = new StringBuilder("运行中的进程:\n");
        int count = Math.min(processes.size(), 10);  // 最多显示10个
        for (int i = 0; i < count; i++) {
            sb.append(processes.get(i).toString()).append("\n");
        }
        if (processes.size() > 10) {
            sb.append("... 及其他 ").append(processes.size() - 10).append(" 个进程");
        }
        Toast.makeText(context, sb.toString(), Toast.LENGTH_LONG).show();
    }

    /**
     * 对选中的进程执行 Root 内存脱壳
     */
    private void executeDump(ProcessEntry entry) {
        Toast.makeText(context, "开始脱壳: " + entry.name + " ...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                // 确定输出目录
                // 输出到 /sdcard/cDumpDex/dump/ 方便文件管理器访问
                File outputDir = new File(
                        "/storage/emulated/0/cDumpDex/dump",
                        "root_memory_" + System.currentTimeMillis());
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    Log.w(TAG, "Failed to create output dir: " + outputDir.getAbsolutePath());
                }

                RootMemoryScanner scanner = new RootMemoryScanner(context);
                int count = scanner.dumpDexFromPid(entry.pid, outputDir);

                final String msg;
                if (count >= 0) {
                    msg = "脱壳完成: " + entry.name + "\n找到 " + count + " 个DEX\n保存到: " + outputDir.getAbsolutePath();
                } else {
                    msg = "脱壳失败: " + entry.name + " (错误码: " + count + ")";
                }

                mainHandler.post(() -> {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                });

                Log.d(TAG, "Dump result for " + entry.name + ": " + count + " dex files");
            } catch (Exception e) {
                Log.e(TAG, "Dump failed for " + entry.name, e);
                mainHandler.post(() -> {
                    Toast.makeText(context, "脱壳失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
