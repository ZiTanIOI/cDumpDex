package com.zitan.cdumpdex;

import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Smali编译工具类
 * 将smali代码编译为dex文件
 *
 * 使用 smali 3.x (com.android.tools.smali:smali) 的 API:
 *   Smali.assemble(SmaliOptions, String... 输入文件) -> boolean
 * 流程: 写入临时 .smali 文件 -> options.outputDexFile 指向输出 -> assemble。
 */
public class SmaliCompiler {

    private int apiLevel = 35; // 默认API级别

    /**
     * 设置API级别
     * @param apiLevel Android API级别
     */
    public SmaliCompiler setApiLevel(int apiLevel) {
        // 注意: smali 3.0.9 对 API 36 的 dex 版本映射有 bug(HeaderItem.getMagicForApi 越界),
        // 3.0.10 才修复 Android 16 支持。这里封顶到 35: API 35 编译出的 dex(039)
        // 在 API 36 设备上可正常加载, 功能无差异。
        this.apiLevel = Math.min(apiLevel, 35);
        return this;
    }

    /**
     * 将smali代码字符串编译为dex字节数组
     * @param smaliCode smali代码
     * @param className 类名（用于日志）
     * @return dex字节数组
     * @throws Exception 编译失败时抛出异常
     */
    public byte[] compileSmaliToDex(String smaliCode, String className) throws Exception {
        File tempDir = null;
        File tempDexFile = null;

        try {
            // 创建临时目录
            tempDir = new File(System.getProperty("java.io.tmpdir"), "smali_compile_" + System.currentTimeMillis());
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }

            // 输出dex文件
            tempDexFile = new File(tempDir, "output.dex");

            // 编译
            boolean success = compileWithSmali(smaliCode, tempDexFile);

            if (!success || !tempDexFile.exists()) {
                throw new Exception("Smali compilation failed");
            }

            // 读取生成的dex文件
            FileInputStream fis = new FileInputStream(tempDexFile);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            fis.close();
            return baos.toByteArray();

        } finally {
            // 清理临时文件
            if (tempDir != null && tempDir.exists()) {
                deleteRecursively(tempDir);
            }
        }
    }

    /**
     * 使用 smali 3.x API 编译 smali 代码。
     * 流程: 写入临时 .smali 文件 -> SmaliOptions.outputDexFile 指向输出 -> Smali.assemble。
     */
    private boolean compileWithSmali(String smaliCode, File outputDexFile) {
        File tempSmaliFile = null;
        try {
            // 写入 smali 到临时文件
            tempSmaliFile = File.createTempFile("smali_", ".smali");
            try (FileOutputStream fos = new FileOutputStream(tempSmaliFile)) {
                fos.write(smaliCode.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }

            SmaliOptions options = new SmaliOptions();
            options.apiLevel = apiLevel;
            options.outputDexFile = outputDexFile.getAbsolutePath();
            options.jobs = 1;

            boolean ok = Smali.assemble(options, tempSmaliFile.getAbsolutePath());
            return ok && outputDexFile.exists() && outputDexFile.length() > 0;
        } catch (Throwable t) {
            // 明确打印失败原因(常见: 依赖缺失 NoClassDefFoundError / smali 语法错误)
            t.printStackTrace();
            return false;
        } finally {
            if (tempSmaliFile != null && tempSmaliFile.exists()) {
                tempSmaliFile.delete();
            }
        }
    }

    /**
     * 将smali代码编译并保存为dex文件
     * @param smaliCode smali代码
     * @param className 类名
     * @param outputDexFile 输出dex文件
     * @return 是否成功
     */
    public boolean compileSmaliToDexFile(String smaliCode, String className, File outputDexFile) {
        try {
            byte[] dexBytes = compileSmaliToDex(smaliCode, className);
            ToolClass.createFile(outputDexFile);
            FileOutputStream fos = new FileOutputStream(outputDexFile);
            fos.write(dexBytes);
            fos.flush();
            fos.close();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 删除目录及其内容
     */
    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (file.exists()) {
            file.delete();
        }
    }

    /**
     * 静态方法：快速将smali代码编译为dex字节数组
     * @param smaliCode smali代码
     * @param className 类名
     * @return dex字节数组，失败返回null
     */
    public static byte[] compile(String smaliCode, String className) {
        SmaliCompiler compiler = new SmaliCompiler();
        try {
            return compiler.compileSmaliToDex(smaliCode, className);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 静态方法：快速将smali代码编译为dex文件
     * @param smaliCode smali代码
     * @param className 类名
     * @param outputDexFile 输出dex文件
     * @return 是否成功
     */
    public static boolean compileToFile(String smaliCode, String className, File outputDexFile) {
        SmaliCompiler compiler = new SmaliCompiler();
        return compiler.compileSmaliToDexFile(smaliCode, className, outputDexFile);
    }
}
