package com.zitan.cdumpdex.util;

import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

/**
 * Root工具类
 * 用于通过Root权限向宿主应用的/Android/data目录写入配置文件
 */
public class RootHelper {
    private static final String TAG = "RootHelper";
    private Process suProcess;
    private DataOutputStream suOutputStream;

    /**
     * 检查是否具有Root权限
     */
    public boolean checkRootAccess() {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("id\n");
            os.flush();
            os.writeBytes("exit\n");
            os.flush();
            process.waitFor();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();

            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            Log.e(TAG, "Root check failed", e);
            return false;
        }
    }

    /**
     * 关闭Root Shell
     */
    public void closeShell() {
        try {
            if (suOutputStream != null) {
                suOutputStream.writeBytes("exit\n");
                suOutputStream.flush();
                suOutputStream.close();
            }
            if (suProcess != null) {
                suProcess.waitFor();
                suProcess.destroy();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to close shell", e);
        }
    }

    /**
     * 通过Root Shell写入文件
     */
    public boolean writeConfigToApp(String targetPackage, String fileName, String content) {
        try {
            String basePath = Environment.getExternalStorageDirectory().getPath();
            String targetPath = basePath + "/Android/data/" + targetPackage + "/files";
            String filePath = targetPath + "/" + fileName;

            Log.d(TAG, "Writing to: " + filePath);

            // 使用Root Shell执行命令
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());

            // 创建目录并写入文件
            String escapedContent = content.replace("'", "'\\''");
            String commands = "mkdir -p " + targetPath + "\n" +
                    "echo '" + escapedContent + "' > " + filePath + "\n" +
                    "chmod 666 " + filePath + "\n" +
                    "cat " + filePath + "\n" +
                    "exit\n";

            os.writeBytes(commands);
            os.flush();
            os.close();

            // 读取输出验证
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            reader.close();

            int exitCode = process.waitFor();
            process.destroy();

            String result = output.toString();
            Log.d(TAG, "Root shell exitCode=" + exitCode + ", output: " + result);

            return result.contains("unshell_mode");
        } catch (Exception e) {
            Log.e(TAG, "Failed to write config", e);
            return false;
        }
    }

    /**
     * 向目标应用的配置目录写入JSON配置
     */
    public boolean writeJsonConfig(String targetPackage, String configJson) {
        return writeConfigToApp(targetPackage, "cdumpdex_config.json", configJson);
    }
}
