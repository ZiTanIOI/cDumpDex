package com.zitan.cdumpdex;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

/**
 * Java反编译工具类
 * 使用jadx将dex/class反编译为Java代码
 */
public class JavaDecompileUtils {

    private JadxDecompiler decompiler;
    private JadxArgs args;
    private boolean initialized = false;

    /**
     * 从dex文件初始化反编译器
     * @param dexPath dex文件路径
     */
    public void initFromDex(String dexPath) throws Exception {
        List<File> inputFiles = new ArrayList<>();
        inputFiles.add(new File(dexPath));
        init(inputFiles);
    }

    /**
     * 从多个dex文件初始化反编译器
     * @param dexPaths dex文件路径列表
     */
    public void initFromDexFiles(List<String> dexPaths) throws Exception {
        List<File> inputFiles = new ArrayList<>();
        for (String path : dexPaths) {
            inputFiles.add(new File(path));
        }
        init(inputFiles);
    }

    /**
     * 从dex字节数组初始化反编译器
     * 需要先写入临时文件
     * @param dexBytes dex字节数组
     * @param tempDir 临时目录
     */
    public void initFromDexBytes(byte[] dexBytes, File tempDir) throws Exception {
        File tempDex = new File(tempDir, "temp_" + System.currentTimeMillis() + ".dex");
        ToolClass.createFile(tempDex);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempDex)) {
            fos.write(dexBytes);
            fos.flush();
        }

        List<File> inputFiles = new ArrayList<>();
        inputFiles.add(tempDex);
        init(inputFiles);

        // 标记临时文件需要在关闭时删除
        tempDex.deleteOnExit();
    }

    private void init(List<File> inputFiles) throws Exception {
        args = new JadxArgs();
        args.setInputFiles(inputFiles);
        args.setSkipResources(true);
        args.setShowInconsistentCode(true);
        args.setDebugInfo(false);

        decompiler = new JadxDecompiler(args);

        // Android 上 jadx 的 SPI 插件加载/内部文件读取不可靠(实测 getClasses() 恒为 0 且无报错)。
        // 改为: 手动读 dex 字节 -> DexInputPlugin.loadDex(byte[]) -> addCustomLoad 注入,
        // 完全绕过 ServiceLoader 与 jadx 的 input 文件处理路径。
        for (File f : inputFiles) {
            byte[] dexBytes = readAllBytes(f);
            jadx.plugins.input.dex.DexInputPlugin plugin = new jadx.plugins.input.dex.DexInputPlugin();
            jadx.api.plugins.input.data.ILoadResult loadResult =
                    plugin.loadDex(dexBytes, f.getAbsolutePath());
            if (loadResult != null && !loadResult.isEmpty()) {
                decompiler.addCustomLoad(loadResult);
            }
        }

        decompiler.load();
        initialized = true;
    }

    private static byte[] readAllBytes(File file) throws java.io.IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    /**
     * 反编译指定类为Java代码
     * @param className 类名（Java格式，如com.example.MyClass）
     * @return Java代码字符串，如果找不到返回null
     */
    public String decompileClass(String className) {
        if (!initialized || decompiler == null) {
            return null;
        }

        try {
            // 遍历所有类查找目标类
            for (JavaClass javaClass : decompiler.getClasses()) {
                if (javaClass.getFullName().equals(className)) {
                    return javaClass.getCode();
                }
            }

            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 获取所有类名
     * @return 类名列表
     */
    public List<String> getAllClassNames() {
        List<String> classNames = new ArrayList<>();
        if (!initialized || decompiler == null) {
            return classNames;
        }

        for (JavaClass javaClass : decompiler.getClasses()) {
            classNames.add(javaClass.getFullName());
        }
        return classNames;
    }

    /**
     * 检查是否包含指定类
     * @param className 类名
     * @return 是否包含
     */
    public boolean containsClass(String className) {
        if (!initialized || decompiler == null) {
            return false;
        }

        for (JavaClass javaClass : decompiler.getClasses()) {
            if (javaClass.getFullName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取指定类的JavaClass对象
     * @param className 类名
     * @return JavaClass对象，如果找不到返回null
     */
    public JavaClass getJavaClass(String className) {
        if (!initialized || decompiler == null) {
            return null;
        }

        for (JavaClass javaClass : decompiler.getClasses()) {
            if (javaClass.getFullName().equals(className)) {
                return javaClass;
            }
        }
        return null;
    }

    /**
     * 关闭反编译器，释放资源
     */
    public void close() {
        if (decompiler != null) {
            decompiler.close();
            decompiler = null;
        }
        initialized = false;
    }

    /**
     * 检查是否已初始化
     * @return 是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 静态方法：快速反编译单个dex文件中的单个类
     * @param dexPath dex文件路径
     * @param className 类名
     * @return Java代码
     */
    public static String decompileClassFromDex(String dexPath, String className) {
        JavaDecompileUtils utils = new JavaDecompileUtils();
        try {
            utils.initFromDex(dexPath);
            return utils.decompileClass(className);
        } catch (Exception e) {
            // 打印真实失败原因(常见: dex 解析失败/依赖缺失), 供 logcat 排查
            e.printStackTrace();
            return null;
        } finally {
            utils.close();
        }
    }

    /**
     * 静态方法：快速反编译dex字节数组中的单个类
     * @param dexBytes dex字节数组
     * @param className 类名
     * @param tempDir 临时目录
     * @return Java代码
     */
    public static String decompileClassFromBytes(byte[] dexBytes, String className, File tempDir) {
        JavaDecompileUtils utils = new JavaDecompileUtils();
        try {
            utils.initFromDexBytes(dexBytes, tempDir);
            return utils.decompileClass(className);
        } catch (Exception e) {
            return null;
        } finally {
            utils.close();
        }
    }
}
