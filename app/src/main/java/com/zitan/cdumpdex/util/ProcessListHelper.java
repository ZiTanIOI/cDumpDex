package com.zitan.cdumpdex.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 进程列表辅助类
 * 通过 Root shell 获取系统中运行中的进程列表
 */
public class ProcessListHelper {
    private static final String TAG = "ProcessListHelper";

    /**
     * 进程条目
     */
    public static class ProcessEntry {
        public int pid;
        public String name;

        public ProcessEntry(int pid, String name) {
            this.pid = pid;
            this.name = name;
        }

        @Override
        public String toString() {
            return name + " (PID: " + pid + ")";
        }
    }

    /**
     * 获取所有运行中的进程
     * 通过 root shell 执行 ps -A 命令获取完整进程列表
     */
    public static List<ProcessEntry> getRunningProcesses() {
        List<ProcessEntry> processes = new ArrayList<>();

        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());

            // ps -A: 所有进程
            // -o PID,NAME: 只输出 PID 和进程名
            os.writeBytes("ps -A -o PID,NAME 2>/dev/null\n");
            os.flush();
            os.writeBytes("exit\n");
            os.flush();
            os.close();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // 跳过标题行
                if (firstLine) {
                    firstLine = false;
                    if (line.startsWith("PID") || line.startsWith("pid")) continue;
                }

                // 解析 "PID NAME" 格式
                // PID 和 NAME 之间可能有多个空格
                String[] parts = line.split("\\s+", 2);
                if (parts.length >= 2) {
                    try {
                        int pid = Integer.parseInt(parts[0]);
                        String name = parts[1].trim();
                        if (pid > 0) {
                            processes.add(new ProcessEntry(pid, name));
                        }
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "Failed to parse PID from: " + line);
                    }
                }
            }
            reader.close();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            process.destroy();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get running processes", e);
        }

        return processes;
    }

    /**
     * 获取应用进程（过滤掉内核进程和系统服务）
     * 只保留进程名看起来像包名的进程（包含 '.' 字符）
     */
    public static List<ProcessEntry> getAppProcesses() {
        List<ProcessEntry> all = getRunningProcesses();
        List<ProcessEntry> apps = new ArrayList<>();

        for (ProcessEntry entry : all) {
            String name = entry.name;
            // 过滤内核线程 (用方括号括起来的)
            if (name.startsWith("[") && name.endsWith("]")) continue;

            // 过滤常见的系统守护进程
            if (isSystemDaemon(name)) continue;

            // 保留应用进程：包含 '.' 的通常是 Java/Android 应用进程
            if (name.contains(".")) {
                apps.add(entry);
            }
        }

        return apps;
    }

    /**
     * 判断是否为系统守护进程
     */
    private static boolean isSystemDaemon(String name) {
        String[] systemDaemons = {
            "adbd", "audioserver", "binder", "bootanimation", "cameraserver",
            "charger", "debuggerd", "drmserver", "dumpstate", "dumpsys",
            "gatekeeperd", "healthd", "hwservicemanager", "idmap", "incidentd",
            "init", "installd", "keystore", "lmkd", "logcat", "logd",
            "mdnsd", "media", "mediadrmserver", "mediaextractor", "mediametrics",
            "mediaserver", "mtpd", "netd", "perfetto", "racoon",
            "rss_hwm_reset", "sdcard", "sensors", "servicemanager",
            "statsd", "storaged", "surfaceflinger", "swapon",
            "thermalserviced", "tombstoned", "traced", "ueventd",
            "uncrypt", "update_engine", "usbd", "vold", "watchdogd",
            "wdmd", "wificond", "wpa_supplicant", "zygote", "zygote64",
            // Android framework processes
            "system_server", "sh", "su", "ps",
            // Common vendor daemons
            "android.hardware", "android.system", "vendor.",
            // One-off/helper processes
            "iptables", "ip6tables", "tc", "cnss", "cnss-daemon"
        };

        for (String daemon : systemDaemons) {
            if (name.equals(daemon) || name.startsWith(daemon)) return true;
        }

        // 名称全小写且不含 '.' 的通常是系统守护进程
        if (!name.contains(".") && name.equals(name.toLowerCase())) return true;

        return false;
    }

    /**
     * 通过进程名模糊查找 PID（取第一个匹配）
     */
    public static int findPidByName(String namePattern) {
        List<ProcessEntry> all = getRunningProcesses();
        for (ProcessEntry entry : all) {
            if (entry.name.contains(namePattern)) {
                return entry.pid;
            }
        }
        return -1;
    }

    /**
     * 获取所有包含特定包名的进程（主进程和子进程如 :xxx）
     */
    public static List<ProcessEntry> getProcessesByPackage(String packageName) {
        List<ProcessEntry> all = getRunningProcesses();
        List<ProcessEntry> result = new ArrayList<>();

        for (ProcessEntry entry : all) {
            // 主进程: 进程名 == 包名
            // 子进程: 进程名 == 包名:xxx
            if (entry.name.equals(packageName) || entry.name.startsWith(packageName + ":")) {
                result.add(entry);
            }
        }

        return result;
    }
}
