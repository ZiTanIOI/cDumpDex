package com.zitan.cdumpdex.service;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Keep;

import com.zitan.cdumpdex.IFileService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;

/**
 * 运行在 Shizuku 进程中的文件服务
 * 具有 ADB 权限，可以访问其他应用的私有目录
 */
public class FileService extends IFileService.Stub {
    private static final String TAG = "FileService";

    /**
     * 无参构造函数（必需）
     */
    public FileService() {
        Log.i(TAG, "FileService created");
    }

    /**
     * 带 Context 的构造函数（Shizuku API v13+）
     */
    @Keep
    public FileService(Context context) {
        Log.i(TAG, "FileService created with context: " + (context != null ? context.getPackageName() : "null"));
    }

    @Override
    public void destroy() {
        Log.i(TAG, "FileService destroyed");
        System.exit(0);
    }

    @Override
    public boolean writeFile(String path, String content) throws RemoteException {
        try {
            Log.d(TAG, "writeFile: " + path);

            // 确保父目录存在
            File file = new File(path);
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // 写入文件
            FileWriter writer = new FileWriter(file);
            writer.write(content);
            writer.flush();
            writer.close();

            // 设置文件权限为 0666（所有用户可读写）
            // 这样即使文件所有者是 Shell，宿主程序也能读取
            try {
                Process chmodProcess = Runtime.getRuntime().exec(new String[]{"chmod", "666", path});
                chmodProcess.waitFor();
                chmodProcess.destroy();
                Log.d(TAG, "chmod 666 executed for: " + path);
            } catch (Exception e) {
                Log.e(TAG, "chmod failed: " + e.getMessage());
                // 尝试使用 Java API 设置权限（作为备选）
                file.setReadable(true, false);
                file.setWritable(true, false);
            }

            boolean success = file.exists() && file.length() > 0;
            Log.d(TAG, "writeFile result: " + success);
            return success;
        } catch (Exception e) {
            Log.e(TAG, "writeFile failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean mkdir(String path) throws RemoteException {
        try {
            Log.d(TAG, "mkdir: " + path);
            File dir = new File(path);
            if (dir.exists()) {
                return true;
            }
            return dir.mkdirs();
        } catch (Exception e) {
            Log.e(TAG, "mkdir failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean exists(String path) throws RemoteException {
        try {
            return new File(path).exists();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String readFile(String path) throws RemoteException {
        try {
            Log.d(TAG, "readFile: " + path);
            File file = new File(path);
            if (!file.exists()) {
                return null;
            }

            StringBuilder content = new StringBuilder();
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            reader.close();
            return content.toString();
        } catch (Exception e) {
            Log.e(TAG, "readFile failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public String executeCommand(String command) throws RemoteException {
        try {
            Log.d(TAG, "executeCommand: " + command);
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            reader.close();

            process.waitFor();
            process.destroy();

            return output.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "executeCommand failed: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
