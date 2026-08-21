package com.zitan.cdumpdex;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;
import org.jf.smali.SmaliOptions;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Smali编译工具类
 * 将smali代码编译为dex文件
 *
 * 使用dexlib2的DexPool来构建dex文件
 * 注意：这是一个简化实现，主要支持基本的热修复场景
 */
public class SmaliCompiler {

    // 模块自身的 ClassLoader，用于在目标应用中加载 smali 库类
    private static final ClassLoader MODULE_CLASSLOADER = SmaliCompiler.class.getClassLoader();

    private int apiLevel = 35; // 默认API级别

    /**
     * 设置API级别
     * @param apiLevel Android API级别
     */
    public SmaliCompiler setApiLevel(int apiLevel) {
        this.apiLevel = apiLevel;
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

            // 使用dexlib2编译
            boolean success = compileWithDexPool(smaliCode, className, tempDexFile);

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
     * 使用DexPool编译smali代码
     * 通过反射调用smali库的内部类来解析smali
     */
    private boolean compileWithDexPool(String smaliCode, String className, File outputDexFile) {
        try {
            // 创建DexPool
            DexPool dexPool = new DexPool(Opcodes.forApi(apiLevel));

            // 尝试使用反射调用smali库
            // 由于smali库API版本差异，使用反射更安全
            // 使用模块的 ClassLoader 来加载 smali 库类
            ClassDef classDef = null;

            try {
                // 尝试使用 org.jf.smali.Smali 类
                Class<?> smaliClass = MODULE_CLASSLOADER.loadClass("org.jf.smali.Smali");
                java.lang.reflect.Method assembleMethod = null;

                // 尝试不同的方法签名
                try {
                    // 方法1: assemble(String smali, int apiLevel, String sourceName, SmaliOptions options)
                    assembleMethod = smaliClass.getMethod("assemble",
                            String.class, int.class, String.class, SmaliOptions.class);
                    SmaliOptions options = new SmaliOptions();
                    options.apiLevel = apiLevel;
                    classDef = (ClassDef) assembleMethod.invoke(null, smaliCode, apiLevel, className + ".smali", options);
                } catch (NoSuchMethodException e1) {
                    try {
                        // 方法2: assemble(Reader reader, String sourceName, int apiLevel, SmaliOptions options)
                        assembleMethod = smaliClass.getMethod("assemble",
                                java.io.Reader.class, String.class, int.class, SmaliOptions.class);
                        SmaliOptions options = new SmaliOptions();
                        options.apiLevel = apiLevel;
                        classDef = (ClassDef) assembleMethod.invoke(null,
                                new StringReader(smaliCode), className + ".smali", apiLevel, options);
                    } catch (NoSuchMethodException e2) {
                        // 方法3: 尝试其他签名
                        java.lang.reflect.Method[] methods = smaliClass.getDeclaredMethods();
                        for (java.lang.reflect.Method m : methods) {
                            if (m.getName().equals("assemble") && m.getParameterCount() >= 1) {
                                m.setAccessible(true);
                                Class<?>[] paramTypes = m.getParameterTypes();
                                Object[] args = new Object[paramTypes.length];
                                for (int i = 0; i < paramTypes.length; i++) {
                                    if (paramTypes[i] == String.class) {
                                        args[i] = smaliCode;
                                    } else if (paramTypes[i] == int.class || paramTypes[i] == Integer.class) {
                                        args[i] = apiLevel;
                                    } else if (paramTypes[i] == SmaliOptions.class) {
                                        SmaliOptions opts = new SmaliOptions();
                                        opts.apiLevel = apiLevel;
                                        args[i] = opts;
                                    } else if (paramTypes[i] == java.io.Reader.class) {
                                        args[i] = new StringReader(smaliCode);
                                    } else if (paramTypes[i] == InputStream.class) {
                                        args[i] = new ByteArrayInputStream(smaliCode.getBytes(StandardCharsets.UTF_8));
                                    } else {
                                        args[i] = null;
                                    }
                                }
                                Object result = m.invoke(null, args);
                                if (result instanceof ClassDef) {
                                    classDef = (ClassDef) result;
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (classDef != null) {
                // 将ClassDef添加到DexPool
                dexPool.internClass(classDef);

                // 写入dex文件
                dexPool.writeTo(new FileDataStore(outputDexFile));
                return outputDexFile.exists();
            }

            // 如果反射方式失败，使用备用方法
            return compileWithSmaliMain(smaliCode, className, outputDexFile);

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 备用方法：通过调用smali的main方法编译
     */
    private boolean compileWithSmaliMain(String smaliCode, String className, File outputDexFile) {
        File tempDir = null;
        File tempSmaliFile = null;

        try {
            // 创建临时目录和文件
            tempDir = new File(System.getProperty("java.io.tmpdir"), "smali_main_" + System.currentTimeMillis());
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }

            // 写入smali文件
            String smaliFileName = className.replace('.', File.separatorChar) + ".smali";
            tempSmaliFile = new File(tempDir, smaliFileName);
            tempSmaliFile.getParentFile().mkdirs();

            FileOutputStream fos = new FileOutputStream(tempSmaliFile);
            fos.write(smaliCode.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();

            // 调用smali的main方法
            String[] args = {
                    "a",  // assemble命令
                    "-a", String.valueOf(apiLevel),
                    "-o", outputDexFile.getAbsolutePath(),
                    tempDir.getAbsolutePath()
            };

            // 使用反射调用main方法（使用模块的 ClassLoader）
            Class<?> mainClass = MODULE_CLASSLOADER.loadClass("org.jf.smali.Main");
            java.lang.reflect.Method mainMethod = mainClass.getMethod("main", String[].class);

            // 保存原始System.out
            PrintStream originalOut = System.out;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            System.setOut(new PrintStream(baos));

            try {
                mainMethod.invoke(null, (Object) args);
            } finally {
                System.setOut(originalOut);
            }

            return outputDexFile.exists();

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (tempDir != null && tempDir.exists()) {
                deleteRecursively(tempDir);
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
