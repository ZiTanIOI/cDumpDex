package com.zitan.cdumpdex;

import static com.zitan.cdumpdex.MainHook.log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class ToolClass {

    private static void createFile(File file, boolean isDirectory) throws IOException {
        if (file == null) {
            throw new IOException("目标路径为空");
        }
        if (isDirectory) {
            if (file.exists()) {
                if (!file.isDirectory()) {
                    throw new IOException("目标已存在但不是文件夹: " + file.getAbsolutePath());
                }
                return;
            }
            if (!file.mkdirs() && !file.isDirectory()) {
                throw new IOException("文件夹创建失败: " + file.getAbsolutePath());
            }
        } else {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("父文件夹创建失败: " + parent.getAbsolutePath());
            }
            if (file.exists()) {
                if (!file.isFile()) {
                    throw new IOException("目标已存在但不是文件: " + file.getAbsolutePath());
                }
                return;
            }
            if (!file.createNewFile() && !file.isFile()) {
                throw new IOException("文件创建失败: " + file.getAbsolutePath());
            }
        }
    }

    public static void createFile(File file) throws IOException {
        createFile(file, false);
    }

    public static void createDirectory(File file) throws IOException {
        createFile(file, true);
    }

    public static void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) createDirectory(parent);
        try (FileInputStream inputStream = new FileInputStream(source);
             FileOutputStream outputStream = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[10240];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, length);
            }
        }
    }

    public static void copyDirectory(File source, File target) throws IOException {
        if (source == null || !source.exists()) return;
        if (source.isFile()) {
            copyFile(source, target);
            return;
        }
        createDirectory(target);
        File[] children = source.listFiles();
        if (children == null) {
            throw new IOException("无法读取文件夹: " + source.getAbsolutePath());
        }
        for (File child : children) {
            copyDirectory(child, new File(target, child.getName()));
        }
    }

    public static boolean deleteFile(File targetFile) {
        if (targetFile == null || !targetFile.exists()) {
            return false;
        }
        if (targetFile.isDirectory()) {
            File[] files = targetFile.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteFile(file);
                }
            }
        }
        return targetFile.delete();
    }
}
