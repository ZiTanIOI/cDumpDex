package com.zitan.cdumpdex.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Root进程内存扫描器
 *
 * 通过Root权限直接读取目标进程的 /proc/&lt;pid&gt;/mem，扫描并dump内存中的DEX文件。
 * 不依赖Xposed注入，仅需要Root权限。
 *
 * 工作流程：
 * 1. 从assets提取rootdump原生可执行文件到文件目录
 * 2. 通过Root shell获取目标进程PID
 * 3. 通过Root shell执行rootdump进行内存扫描
 * 4. 解析输出获取dump结果
 */
public class RootMemoryScanner {
    private static final String TAG = "RootMemoryScanner";
    private static final String BINARY_NAME = "rootdump";

    private final Context context;
    private File binaryFile;

    /** Quote one argument before sending it to the root shell. */
    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean isValidPackageName(String value) {
        return value != null && value.length() <= 255
                && value.matches("[A-Za-z0-9._:-]+");
    }

    public RootMemoryScanner(Context context) {
        this.context = context;
    }

    /**
     * 准备原生可执行文件
     * 从assets中根据ABI提取对应版本到app文件目录，并设置可执行权限
     */
    public boolean prepareBinary() {
        String abi = getAbiSuffix();
        String assetName = BINARY_NAME + "_" + abi;
        binaryFile = new File(context.getFilesDir(), BINARY_NAME);

        // 始终重新提取，确保使用最新版本（避免使用缓存的旧二进制）
        if (binaryFile.exists()) {
            binaryFile.delete();
        }

        try {
            // 从assets复制
            InputStream is = context.getAssets().open(assetName);
            FileOutputStream fos = new FileOutputStream(binaryFile);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = is.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            fos.close();
            is.close();

            // 设置可执行权限 (不需要root，因为是app自己的文件目录)
            if (!binaryFile.setExecutable(true, false)) {
                // 某些设备可能需要通过命令设置
                Runtime.getRuntime().exec("chmod 755 " + binaryFile.getAbsolutePath());
            }

            Log.d(TAG, "Binary prepared: " + binaryFile.getAbsolutePath()
                    + " (" + binaryFile.length() + " bytes)");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to prepare binary: " + assetName, e);
            return false;
        }
    }

    /**
     * 获取当前设备ABI对应的asset后缀
     */
    private String getAbiSuffix() {
        // 按优先级获取支持的ABI
        String[] abis = Build.SUPPORTED_ABIS;
        for (String abi : abis) {
            if (abi.contains("arm64") || abi.contains("aarch64")) {
                return "arm64-v8a";
            }
            if (abi.contains("armeabi") || abi.contains("armv7")) {
                return "armeabi-v7a";
            }
        }
        // 默认用arm64
        Log.w(TAG, "Unknown ABI, defaulting to arm64-v8a. SUPPORTED_ABIS[0]=" + abis[0]);
        return "arm64-v8a";
    }

    /**
     * 通过包名获取目标进程PID
     */
    public int getPidByPackage(String packageName) {
        if (!isValidPackageName(packageName)) {
            Log.w(TAG, "Invalid package name");
            return -1;
        }
        try {
            // 使用 root 权限的 pidof 命令
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());

            os.writeBytes("pidof -- " + shellQuote(packageName) + "\n");
            os.flush();
            os.writeBytes("exit\n");
            os.flush();
            os.close();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            process.destroy();

            if (line != null && !line.isEmpty()) {
                // pidof 可能返回多个PID（多进程应用），取第一个（主进程）
                String[] pids = line.trim().split("\\s+");
                int pid = Integer.parseInt(pids[0]);
                Log.d(TAG, "Found PID for " + packageName + ": " + pid);
                return pid;
            }

            Log.w(TAG, "pidof failed for " + packageName + ", trying ps fallback");
            return getPidByPs(packageName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get PID for " + packageName, e);
            return -1;
        }
    }

    /**
     * 备用方法：通过ps命令获取PID
     */
    private int getPidByPs(String packageName) throws Exception {
        if (!isValidPackageName(packageName)) {
            Log.w(TAG, "Invalid package name: " + packageName);
            return -1;
        }
        Process process = Runtime.getRuntime().exec("su");
        DataOutputStream os = new DataOutputStream(process.getOutputStream());

        // 使用 ps 查找进程，输出PID和进程名
        os.writeBytes("ps -A -o PID,NAME 2>/dev/null | grep -- " + shellQuote(packageName) + "\n");
        os.flush();
        os.writeBytes("exit\n");
        os.flush();
        os.close();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.contains(packageName)) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 1) {
                    try {
                        return Integer.parseInt(parts[0]);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        reader.close();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        process.destroy();
        return -1;
    }

    /**
     * 执行Root内存dump
     *
     * @param packageName 目标应用包名
     * @param outputDir   输出目录
     * @return dump的DEX文件数量，-1表示失败
     */
    public int dumpDexFromProcess(String packageName, File outputDir) {
        if (!prepareBinary()) {
            Log.e(TAG, "Failed to prepare native binary");
            return -1;
        }

        int pid = getPidByPackage(packageName);
        if (pid <= 0) {
            Log.e(TAG, "Failed to find PID for " + packageName);
            return -2;
        }

        return executeRootDump(pid, outputDir);
    }

    /**
     * 通过PID执行Root内存dump
     */
    public int dumpDexFromPid(int pid, File outputDir) {

        return dumpDexFromPidInternal(pid, outputDir);
    }

    /** Execute the root memory scanner without process injection. */
    private int dumpDexFromPidInternal(int pid, File outputDir) {
        if (pid <= 0 || outputDir == null) {
            Log.e(TAG, "Invalid dump target");
            return -2;
        }
        if (!prepareBinary()) {
            Log.e(TAG, "Failed to prepare native binary");
            return -1;
        }
        return executeRootDump(pid, outputDir);
    }

    private int executeRootDump(int pid, File outputDir) {
        if (!outputDir.exists()) {
            if (!outputDir.mkdirs()) {
                Log.w(TAG, "Failed to create output dir: " + outputDir.getAbsolutePath());
            }
        }
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            String cmd = shellQuote(binaryFile.getAbsolutePath()) + " " + pid + " "
                    + shellQuote(outputDir.getAbsolutePath()) + "\n";
            Log.d(TAG, "Executing: " + cmd.trim());
            os.writeBytes(cmd);
            os.writeBytes("exit\n");
            os.flush();
            os.close();

            // Read stdout and stderr concurrently to avoid deadlock
            // (stderr buffer filling up blocks the process, while we wait on stdout)
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();

            Thread stdoutThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d(TAG, "[rootdump] " + line);
                        synchronized (output) { output.append(line).append("\n"); }
                    }
                } catch (IOException e) {
                    Log.w(TAG, "stdout read error: " + e.getMessage());
                }
            }, "rootdump-stdout");
            stdoutThread.start();

            Thread stderrThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        Log.w(TAG, "[rootdump-err] " + line);
                        synchronized (errorOutput) { errorOutput.append(line).append("\n"); }
                    }
                } catch (IOException e) {
                    Log.w(TAG, "stderr read error: " + e.getMessage());
                }
            }, "rootdump-stderr");
            stderrThread.start();

            // Wait for process with timeout (5 minutes)
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                Log.e(TAG, "rootdump timed out after 5 minutes, destroying process");
                process.destroyForcibly();
                stdoutThread.interrupt();
                stderrThread.interrupt();
                return -5;
            }

            stdoutThread.join(5000);
            stderrThread.join(5000);
            reader.close();
            errorReader.close();

            int exitCode = process.exitValue();
            process.destroy();
            Log.d(TAG, "rootdump exit code: " + exitCode);

            String result;
            synchronized (output) { result = output.toString(); }
            for (String rl : result.split("\n")) {
                if (rl.startsWith("DUMP_COUNT:")) {
                    try { return Integer.parseInt(rl.substring("DUMP_COUNT:".length()).trim()); }
                    catch (NumberFormatException e) { Log.w(TAG, "Bad DUMP_COUNT"); }
                }
            }
            if (exitCode != 0) { Log.e(TAG, "rootdump exit code " + exitCode); return -4; }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "rootdump failed", e);
            return -3;
        }
    }
    /**
     * 检查目标进程是否正在运行
     */
    public boolean isProcessRunning(String packageName) {
        return getPidByPackage(packageName) > 0;
    }

    /**
     * 检查Root权限是否可用
     */
    public static boolean checkRootAvailable() {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("id\n");
            os.flush();
            os.writeBytes("exit\n");
            os.flush();
            os.close();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            process.destroy();

            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }
}
