package com.zitan.cdumpdex;

import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.baksmali.formatter.BaksmaliWriter;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.analysis.ClassPath;
import org.jf.dexlib2.analysis.DexClassProvider;
import org.jf.dexlib2.dexbacked.DexBackedClassDef;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexFile;

public class SmaliUtils {
    private final BaksmaliOptions baksmaliOptions;
    private final ClassPath classPath;
    private final DexBackedDexFile dexBackedDexFile;

    public SmaliUtils(InputStream dexInputStream) throws IOException {
        Opcodes opcodes = Opcodes.getDefault();
        baksmaliOptions = new BaksmaliOptions();
        dexBackedDexFile = DexBackedDexFile.fromInputStream(opcodes, new BufferedInputStream(dexInputStream));
        DexClassProvider dexClassProvider = new DexClassProvider(dexBackedDexFile);
        classPath = new ClassPath(dexClassProvider);
    }

    public SmaliUtils(DexBackedDexFile dexFile) throws IOException {
        this.baksmaliOptions = new BaksmaliOptions();
        this.dexBackedDexFile = dexFile;
        DexClassProvider dexClassProvider = new DexClassProvider(dexFile);
        this.classPath = new ClassPath(dexClassProvider);
    }

    public String getSmali(String className) throws IOException {
        ClassDef classDef = classPath.getClassDef(className);
        ClassDefinition classDefinition = new ClassDefinition(baksmaliOptions, classDef);
        StringWriter stringWriter = new StringWriter();
        BaksmaliWriter baksmaliWriter = new BaksmaliWriter(stringWriter);
        classDefinition.writeTo(baksmaliWriter);
        baksmaliWriter.close();
        return stringWriter.toString();
    }

    /**
     * 从DexBackedDexFile中获取指定类的smali代码
     * @param dexFile DexBackedDexFile实例
     * @param className 类名（Java格式，如com.example.MyClass）
     * @return smali代码字符串，如果找不到返回null
     */
    public static String getSmaliFromClassDef(DexBackedDexFile dexFile, String className) throws IOException {
        // 将Java格式的类名转换为smali格式
        String smaliClassName = "L" + className.replace('.', '/') + ";";

        for (DexBackedClassDef classDef : dexFile.getClasses()) {
            if (classDef.getType().equals(smaliClassName)) {
                BaksmaliOptions options = new BaksmaliOptions();
                ClassDefinition classDefinition = new ClassDefinition(options, classDef);
                StringWriter stringWriter = new StringWriter();
                BaksmaliWriter baksmaliWriter = new BaksmaliWriter(stringWriter);
                classDefinition.writeTo(baksmaliWriter);
                baksmaliWriter.close();
                return stringWriter.toString();
            }
        }
        return null;
    }

    /**
     * 从DexBackedDexFile中获取指定类的ClassDef
     * @param dexFile DexBackedDexFile实例
     * @param className 类名（Java格式）
     * @return ClassDef，如果找不到返回null
     */
    public static DexBackedClassDef findClassDef(DexBackedDexFile dexFile, String className) {
        String smaliClassName = "L" + className.replace('.', '/') + ";";
        for (DexBackedClassDef classDef : dexFile.getClasses()) {
            if (classDef.getType().equals(smaliClassName)) {
                return classDef;
            }
        }
        return null;
    }

    /**
     * 从dex文件路径创建DexBackedDexFile
     * @param dexPath dex文件路径
     * @return DexBackedDexFile实例
     */
    public static DexBackedDexFile fromDexFile(String dexPath) throws IOException {
        File dexFile = new File(dexPath);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(dexFile)) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
        }
        return fromDexBytes(baos.toByteArray());
    }

    /**
     * 从字节数组创建DexBackedDexFile
     * @param dexBytes dex文件字节数组
     * @return DexBackedDexFile实例
     */
    public static DexBackedDexFile fromDexBytes(byte[] dexBytes) throws IOException {
        Opcodes opcodes = Opcodes.getDefault();
        ByteArrayInputStream bais = new ByteArrayInputStream(dexBytes);
        return DexBackedDexFile.fromInputStream(opcodes, new BufferedInputStream(bais));
    }

    /**
     * 获取DexBackedDexFile中的所有类名
     * @param dexFile DexBackedDexFile实例
     * @return 类名列表（Java格式）
     */
    public static List<String> getAllClassNames(DexBackedDexFile dexFile) {
        List<String> classNames = new ArrayList<>();
        for (DexBackedClassDef classDef : dexFile.getClasses()) {
            String type = classDef.getType();
            // 转换smali格式到Java格式
            if (type.startsWith("L") && type.endsWith(";")) {
                String javaName = type.substring(1, type.length() - 1).replace('/', '.');
                classNames.add(javaName);
            }
        }
        return classNames;
    }

    /**
     * 检查DexBackedDexFile中是否包含指定类
     * @param dexFile DexBackedDexFile实例
     * @param className 类名（Java格式）
     * @return 是否包含
     */
    public static boolean containsClass(DexBackedDexFile dexFile, String className) {
        String smaliClassName = "L" + className.replace('.', '/') + ";";
        for (DexBackedClassDef classDef : dexFile.getClasses()) {
            if (classDef.getType().equals(smaliClassName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从DexFile对象读取dex字节数组
     * 需要通过native方法或反射获取
     * @param cookie mCookie数组
     * @param index cookie索引
     * @return dex字节数组
     */
    public static native byte[] readDexBytes(long cookie, int index);
}
