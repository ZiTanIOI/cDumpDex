package com.zitan.cdumpdex.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.zitan.cdumpdex.IFileService;
import com.zitan.cdumpdex.service.FileService;

import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Shizuku工具类
 * 用于通过Shizuku向宿主应用的/Android/data目录写入配置文件
 */
public class ShizukuHelper {
    private static final String TAG = "ShizukuHelper";
    private static final String ZERO_WIDTH_SPACE = "\u200b";

    private final Context context;

    // IUserService 相关
    private IFileService fileService;
    private final CountDownLatch serviceLatch = new CountDownLatch(1);
    private boolean serviceConnected = false;

    private final Shizuku.UserServiceArgs userServiceArgs;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.d(TAG, "FileService connected: " + name);
            if (binder != null && binder.pingBinder()) {
                fileService = IFileService.Stub.asInterface(binder);
                serviceConnected = true;
            }
            serviceLatch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "FileService disconnected: " + name);
            fileService = null;
            serviceConnected = false;
        }
    };

    public ShizukuHelper(Context context) {
        this.context = context;

        // 配置 UserService 参数
        userServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(context.getPackageName(), FileService.class.getName())
        )
                .daemon(false)
                .processNameSuffix("file_service")
                .debuggable(false)
                .version(1);
    }

    // ==================== 零宽字符漏洞相关方法 ====================

    public static File getReviseFile(File file) {
        if (Build.VERSION.SDK_INT < 30) return file;
        if (file == null) return null;

        String androidPath = Environment.getExternalStorageDirectory().getPath() + "/Android/";
        String canPath = getCanonicalPath(file);

        if (canPath.length() > androidPath.length() && canPath.toLowerCase().startsWith(androidPath.toLowerCase())) {
            return new File(androidPath + ZERO_WIDTH_SPACE + canPath.substring(androidPath.length()));
        }
        return file;
    }

    public static File getReviseFile(String path) {
        return path == null ? null : getReviseFile(new File(path));
    }

    private static String getCanonicalPath(File file) {
        if (file == null) return null;
        try {
            return file.getCanonicalPath();
        } catch (Exception e) {
            return file.getAbsolutePath();
        }
    }

    /**
     * 使用Unicode零宽字符漏洞写入文件
     */
    public boolean writeWithZeroWidthChar(String targetPackage, String fileName, String content) {
        try {
            String originalPath = Environment.getExternalStorageDirectory().getPath() + "/Android/data/" + targetPackage + "/files/" + fileName;
            File targetFile = getReviseFile(originalPath);

            Log.d(TAG, "Original path: " + originalPath);
            Log.d(TAG, "Revised path: " + targetFile.getAbsolutePath());

            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            FileWriter writer = new FileWriter(targetFile);
            writer.write(content);
            writer.flush();
            writer.close();

            // 使用 Java API 设置文件权限
            // setReadable(true, true) = 仅所有者可读
            // setReadable(true, false) = 所有人可读
            // setWritable(true, false) = 所有人可写
            // 零宽字符路径不能用 chmod 命令，因为命令行需要原始路径
            boolean readAll = targetFile.setReadable(true, false);
            boolean writeAll = targetFile.setWritable(true, false);
            Log.d(TAG, "setReadable(all): " + readAll + ", setWritable(all): " + writeAll);

            boolean exists = targetFile.exists() && targetFile.length() > 0;
            Log.d(TAG, "File exists: " + exists);
            return exists;
        } catch (Throwable e) {
            Log.e(TAG, "Zero-width char exploit failed: " + e.getMessage(), e);
            return false;
        }
    }

    // ==================== Shizuku相关方法 ====================

    public boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            Log.e(TAG, "Shizuku not available: " + e.getMessage());
            return false;
        }
    }

    public boolean hasPermission() {
        try {
            if (!isShizukuAvailable()) return false;
            if (Shizuku.isPreV11()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            Log.e(TAG, "Permission check failed: " + e.getMessage());
            return false;
        }
    }

    public void requestPermission(PermissionCallback callback) {
        try {
            if (!isShizukuAvailable()) {
                callback.onDenied();
                return;
            }

            if (hasPermission()) {
                callback.onGranted();
                return;
            }

            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    try {
                        Shizuku.removeRequestPermissionResultListener(this);
                    } catch (Throwable ignored) {}
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        callback.onGranted();
                    } else {
                        callback.onDenied();
                    }
                }
            });

            Shizuku.requestPermission(0);
        } catch (Throwable e) {
            Log.e(TAG, "Request permission failed: " + e.getMessage());
            callback.onDenied();
        }
    }

    /**
     * 绑定 FileService
     */
    private boolean bindFileService() {
        if (serviceConnected && fileService != null) {
            return true;
        }

        try {
            if (Shizuku.getVersion() < 10) {
                Log.e(TAG, "Shizuku API 10+ required for UserService");
                return false;
            }

            Shizuku.bindUserService(userServiceArgs, serviceConnection);

            // 等待服务连接
            boolean connected = serviceLatch.await(5, TimeUnit.SECONDS);
            if (!connected) {
                Log.e(TAG, "Timeout waiting for FileService connection");
                return false;
            }

            return serviceConnected && fileService != null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind FileService: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 解绑 FileService
     */
    public void unbindFileService() {
        try {
            if (serviceConnected) {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true);
                fileService = null;
                serviceConnected = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to unbind FileService: " + e.getMessage());
        }
    }

    /**
     * 通过 Shizuku UserService 写入文件
     */
    public boolean writeWithShizukuShell(String targetPackage, String fileName, String content) {
        if (!hasPermission()) {
            Log.e(TAG, "No Shizuku permission");
            return false;
        }

        try {
            // 绑定服务
            if (!bindFileService()) {
                Log.e(TAG, "Failed to bind FileService");
                return false;
            }

            String targetPath = Environment.getExternalStorageDirectory().getPath() + "/Android/data/" + targetPackage + "/files";
            String filePath = targetPath + "/" + fileName;

            Log.d(TAG, "Writing to: " + filePath);

            // 创建目录
            boolean mkdirResult = fileService.mkdir(targetPath);
            Log.d(TAG, "mkdir result: " + mkdirResult);

            // 写入文件
            boolean writeResult = fileService.writeFile(filePath, content);
            Log.d(TAG, "write result: " + writeResult);

            if (!writeResult) {
                return false;
            }

            // 验证文件
            String readContent = fileService.readFile(filePath);
            boolean success = readContent != null && readContent.contains("unshell_mode");
            Log.d(TAG, "verify result: " + success);

            return success;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Exception: " + e.getMessage(), e);
            return false;
        }
    }

    public boolean writeJsonConfig(String targetPackage, String configJson) {
        return writeWithShizukuShell(targetPackage, "cdumpdex_config.json", configJson);
    }

    public interface PermissionCallback {
        void onGranted();
        void onDenied();
    }
}
