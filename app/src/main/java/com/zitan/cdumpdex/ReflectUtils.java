package com.zitan.cdumpdex;

import android.app.Activity;
import android.app.ActivityThread;
import android.os.Build;
import android.util.ArrayMap;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexFile;

public class ReflectUtils {

    public static Field getField(Class<?> targetClass, String name) throws NoSuchFieldException {
        // 优先纯反射: 在 LSPosed 环境 hidden API 已豁免, getDeclaredField 直接可用。
        // 避免依赖 HiddenApiBypass 运行时版本(框架注入的旧版可能没有 getInstanceFields)。
        try {
            Field field = targetClass.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            // 兜底: 遍历父类 + HiddenApiBypass(若可用)
        }
        // 遍历继承链查找(某些字段定义在父类)
        for (Class<?> c = targetClass; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                List<Field> fieldList = HiddenApiBypass.getInstanceFields(targetClass);
                fieldList.addAll(HiddenApiBypass.getStaticFields(targetClass));
                for (Field field : fieldList) {
                    if (field.getName().equals(name)) {
                        field.setAccessible(true);
                        return field;
                    }
                }
            } catch (Throwable ignored) {
                // HiddenApiBypass 版本不兼容时忽略
            }
        }
        throw new NoSuchFieldException(name + " in " + targetClass.getName());
    }

    public static Method getMethod(Class<?> targetClass, String name, Class<?>... paramsClass) throws NoSuchMethodException {
        Method method;
        // 优先纯反射(LSPosed 环境 hidden API 已豁免), HiddenApiBypass 兜底
        try {
            method = targetClass.getDeclaredMethod(name, paramsClass);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                method = HiddenApiBypass.getDeclaredMethod(targetClass, name, paramsClass);
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                throw new NoSuchMethodException(name);
            }
        } else {
            method = targetClass.getDeclaredMethod(name, paramsClass);
        }
        method.setAccessible(true);
        return method;
    }

    public static Object getPathList(BaseDexClassLoader targetClassLoader) throws NoSuchFieldException, IllegalAccessException {
        Field pathListField = getField(BaseDexClassLoader.class, "pathList");
        return pathListField.get(targetClassLoader);
    }

    public static Object[] getDexElements(Object pathList) throws NoSuchFieldException, IllegalAccessException {
        Field dexElementsField = getField(pathList.getClass(), "dexElements");
        return (Object[]) dexElementsField.get(pathList);
    }

    public static DexFile getDexFile(Object dexElement) throws NoSuchFieldException, IllegalAccessException {
        Field dexFileField = getField(dexElement.getClass(), "dexFile");
        return (DexFile) dexFileField.get(dexElement);
    }

    public static Object getMCookie(DexFile dexFile) throws NoSuchFieldException, IllegalAccessException {
        try {
            Field mCookieField = getField(DexFile.class, "mCookie");
            Object cookie = mCookieField.get(dexFile);
            if (cookie != null) return cookie;
        } catch (NoSuchFieldException ignored) {
            // 某些厂商 ART 仅保留 mInternalCookie。
        }
        try {
            Field internalCookieField = getField(DexFile.class, "mInternalCookie");
            return internalCookieField.get(dexFile);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    public static String[] getNameList(Object mCookie) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method getClassNameList = getMethod(DexFile.class, "getClassNameList", Object.class);
        return (String[]) getClassNameList.invoke(null, mCookie);
    }

    public static void setDexElements(Object pathList, Object[] dexElements) throws NoSuchFieldException, IllegalAccessException {
        Field dexElementsField = getField(pathList.getClass(), "dexElements");
        dexElementsField.set(pathList, dexElements);
    }

    public static <T> List<T> castList(Object obj, Class<T> clazz) {
        if (obj instanceof List<?>) {
            List<?> tempList = (List<?>) obj;
            List<T> result = new ArrayList<>();
            for (Object item : tempList) {
                if (clazz.isInstance(item)) {
                    result.add(clazz.cast(item));
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    public static List<File> getNativeLibraryDirectories(Object pathList) throws NoSuchFieldException, IllegalAccessException {
        Field nativeLibraryDirectoriesField = getField(pathList.getClass(), "nativeLibraryDirectories");
        Object nativeLibraryDirectories = nativeLibraryDirectoriesField.get(pathList);
        return castList(nativeLibraryDirectories, File.class);
    }

    public static Object[] getNativeLibraryPathElements(Object pathList) throws NoSuchFieldException, IllegalAccessException {
        Field nativeLibraryPathElementsField = getField(pathList.getClass(), "nativeLibraryPathElements");
        return (Object[]) nativeLibraryPathElementsField.get(pathList);
    }

    public static void setNativeLibraryPathElements(Object pathList, Object[] nativeLibraryPathElements) throws NoSuchFieldException, IllegalAccessException {
        Field nativeLibraryPathElementsField = getField(pathList.getClass(), "nativeLibraryPathElements");
        nativeLibraryPathElementsField.set(pathList, nativeLibraryPathElements);
    }

    public static void addNativePath(BaseDexClassLoader targetClassLoader, String path) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        List<String> nativePath = new ArrayList<>();
        nativePath.add(path);
        Method addNativePath = getMethod(BaseDexClassLoader.class, "addNativePath", Collection.class);
        addNativePath.invoke(targetClassLoader, nativePath);
    }

    public static ArrayMap<?, ?> getMActivities(Object activityThread) throws NoSuchFieldException, IllegalAccessException {
        Field mActivitiesField = getField(ActivityThread.class, "mActivities");
        return (ArrayMap<?, ?>) mActivitiesField.get(activityThread);
    }

    public static Activity getActivity(Object activityRecord) throws IllegalAccessException, NoSuchFieldException {
        Field activityField = getField(activityRecord.getClass(), "activity");
        return (Activity) activityField.get(activityRecord);
    }

    public static void invokeLoadLibrary(Runtime runtime, Class<?> fromClass, String libName) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method loadLibrary0 = getMethod(Runtime.class, "loadLibrary0", Class.class, String.class);
        loadLibrary0.invoke(runtime, fromClass, libName);
    }

    // ==================== 类结构分析方法 ====================

    /**
     * 获取所有构造函数
     */
    public static List<Constructor<?>> getAllConstructors(Class<?> clazz) {
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        List<Constructor<?>> result = new ArrayList<>();
        for (Constructor<?> c : constructors) {
            c.setAccessible(true);
            result.add(c);
        }
        return result;
    }

    /**
     * 获取所有方法
     */
    public static List<Method> getAllMethods(Class<?> clazz) {
        List<Method> result = new ArrayList<>();
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                m.setAccessible(true);
                result.add(m);
            }
        } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                List<java.lang.reflect.Executable> executables = HiddenApiBypass.getDeclaredMethods(clazz);
                for (java.lang.reflect.Executable e : executables) {
                    if (e instanceof Method) {
                        e.setAccessible(true);
                        result.add((Method) e);
                    }
                }
            } catch (Throwable ignored) {}
        }
        return result;
    }

    /**
     * 获取所有字段
     */
    public static List<Field> getAllFields(Class<?> clazz) {
        List<Field> result = new ArrayList<>();
        try {
            for (Field f : clazz.getDeclaredFields()) {
                f.setAccessible(true);
                result.add(f);
            }
        } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                result.addAll(HiddenApiBypass.getInstanceFields(clazz));
                result.addAll(HiddenApiBypass.getStaticFields(clazz));
            } catch (Throwable ignored) {}
        }
        return result;
    }

    /**
     * 获取类继承链
     */
    public static List<Class<?>> getInheritanceChain(Class<?> clazz) {
        List<Class<?>> chain = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null) {
            chain.add(current);
            current = current.getSuperclass();
        }
        return chain;
    }

    /**
     * 获取实现的接口
     */
    public static List<Class<?>> getImplementedInterfaces(Class<?> clazz) {
        List<Class<?>> interfaces = new ArrayList<>();
        for (Class<?> iface : clazz.getInterfaces()) {
            interfaces.add(iface);
        }
        return interfaces;
    }

    /**
     * 获取字段的值
     */
    public static Object getFieldValue(Field field, Object instance) throws IllegalAccessException {
        field.setAccessible(true);
        return field.get(instance);
    }

    /**
     * 设置字段的值
     */
    public static void setFieldValue(Field field, Object instance, Object value) throws IllegalAccessException {
        field.setAccessible(true);
        field.set(instance, value);
    }

    /**
     * 调用方法
     */
    public static Object invokeMethod(Method method, Object instance, Object... args)
            throws IllegalAccessException, InvocationTargetException {
        method.setAccessible(true);
        return method.invoke(instance, args);
    }

    /**
     * 创建实例
     */
    public static Object newInstance(Constructor<?> constructor, Object... args)
            throws InstantiationException, IllegalAccessException, InvocationTargetException {
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    /**
     * 获取字段修饰符字符串
     */
    public static String getModifierString(int modifiers) {
        StringBuilder sb = new StringBuilder();
        if (Modifier.isPublic(modifiers)) sb.append("public ");
        else if (Modifier.isPrivate(modifiers)) sb.append("private ");
        else if (Modifier.isProtected(modifiers)) sb.append("protected ");

        if (Modifier.isStatic(modifiers)) sb.append("static ");
        if (Modifier.isFinal(modifiers)) sb.append("final ");
        if (Modifier.isVolatile(modifiers)) sb.append("volatile ");
        if (Modifier.isTransient(modifiers)) sb.append("transient ");
        if (Modifier.isSynchronized(modifiers)) sb.append("synchronized ");
        if (Modifier.isNative(modifiers)) sb.append("native ");
        if (Modifier.isAbstract(modifiers)) sb.append("abstract ");

        return sb.toString().trim();
    }

    /**
     * 格式化参数类型列表
     */
    public static String formatParameterTypes(Class<?>[] paramTypes) {
        if (paramTypes == null || paramTypes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes[i].getSimpleName());
        }
        return sb.toString();
    }

    /**
     * 获取方法签名字符串
     */
    public static String getMethodSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(getModifierString(method.getModifiers()));
        if (sb.length() > 0) sb.append(" ");
        sb.append(method.getReturnType().getSimpleName());
        sb.append(" ");
        sb.append(method.getName());
        sb.append("(");
        sb.append(formatParameterTypes(method.getParameterTypes()));
        sb.append(")");
        return sb.toString();
    }

    /**
     * 获取字段签名字符串
     */
    public static String getFieldSignature(Field field) {
        StringBuilder sb = new StringBuilder();
        sb.append(getModifierString(field.getModifiers()));
        if (sb.length() > 0) sb.append(" ");
        sb.append(field.getType().getSimpleName());
        sb.append(" ");
        sb.append(field.getName());
        return sb.toString();
    }

    /**
     * 获取构造函数签名字符串
     */
    public static String getConstructorSignature(Constructor<?> constructor) {
        StringBuilder sb = new StringBuilder();
        sb.append(getModifierString(constructor.getModifiers()));
        if (sb.length() > 0) sb.append(" ");
        sb.append(constructor.getDeclaringClass().getSimpleName());
        sb.append("(");
        sb.append(formatParameterTypes(constructor.getParameterTypes()));
        sb.append(")");
        return sb.toString();
    }

    /**
     * 格式化值显示
     */
    public static String formatValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + value + "\"";
        if (value instanceof Character) return "'" + value + "'";
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            return value.getClass().getComponentType().getSimpleName() + "[" + length + "]";
        }
        return String.valueOf(value);
    }

    /**
     * 获取 JSONObject 的所有 key
     */
    public static List<String> keys(org.json.JSONObject json) {
        List<String> keys = new ArrayList<>();
        Iterator<String> it = json.keys();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        return keys;
    }

    /**
     * 检查是否是基本类型或其包装类
     */
    public static boolean isPrimitiveOrWrapper(Class<?> clazz) {
        return clazz.isPrimitive() ||
               clazz == Integer.class ||
               clazz == Long.class ||
               clazz == Double.class ||
               clazz == Float.class ||
               clazz == Boolean.class ||
               clazz == Byte.class ||
               clazz == Short.class ||
               clazz == Character.class ||
               clazz == String.class;
    }

    /**
     * 尝试从多个 ClassLoader 加载类
     * 用于支持加固应用，这些应用的类可能分布在不同的 ClassLoader 中
     *
     * @param className 类名
     * @param primaryClassLoader 主 ClassLoader
     * @param additionalClassLoaders 额外的 ClassLoader 列表
     * @return 加载的类
     * @throws ClassNotFoundException 所有 ClassLoader 都无法加载该类
     */
    public static Class<?> loadClassFromMultipleLoaders(String className, ClassLoader primaryClassLoader, List<ClassLoader> additionalClassLoaders) throws ClassNotFoundException {
        // 首先尝试主 ClassLoader
        if (primaryClassLoader != null) {
            try {
                return primaryClassLoader.loadClass(className);
            } catch (ClassNotFoundException ignored) {}
        }

        // 尝试额外的 ClassLoader
        if (additionalClassLoaders != null) {
            for (ClassLoader loader : additionalClassLoaders) {
                if (loader != null && loader != primaryClassLoader) {
                    try {
                        return loader.loadClass(className);
                    } catch (ClassNotFoundException ignored) {}
                }
            }
        }

        // 尝试上下文 ClassLoader
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null && contextLoader != primaryClassLoader) {
            try {
                return contextLoader.loadClass(className);
            } catch (ClassNotFoundException ignored) {}
        }

        // 尝试系统 ClassLoader
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        if (systemLoader != null && systemLoader != primaryClassLoader) {
            try {
                return systemLoader.loadClass(className);
            } catch (ClassNotFoundException ignored) {}
        }

        throw new ClassNotFoundException("Class not found in any available ClassLoader: " + className);
    }

}
