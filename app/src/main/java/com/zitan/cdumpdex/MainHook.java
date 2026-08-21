package com.zitan.cdumpdex;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.ArrayMap;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import com.zitan.cdumpdex.util.RootMemoryScanner;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String DIAG_TAG = "cDumpDex";

    // 脱壳模式常量
    private static final int MODE_FIXED = 0;           // 固定结构映射
    private static final int MODE_MEMORY_SCAN = 1;     // 内存特征匹配
    private static final int MODE_LOADCLASS_HOOK = 2;  // LoadClass Hook
    private static final int MODE_ROOT_MEMORY = 3;     // Root进程内存扫描

    // 浮动图标大小常量
    private static final int FLOAT_ICON_SIZE = 200;

    private File injectPathFile;
    private File dexInjectPathFile;
    private File soInjectPathFile;
    private final ArrayList<String> blackList = new ArrayList<>();
    private final List<BaseDexClassLoader> allClassLoader = Collections.synchronizedList(new ArrayList<>());
    private final List<ViewGroup> viewGroupList = Collections.synchronizedList(new ArrayList<>());
    private File dexDumpFile;
    private File blackListFile;
    private boolean enablePattern = false;
    private boolean injectDexToAllClassLoader = false;
    private boolean activeLoadClass = true; // 主动调用模式，默认启用
    private String activeCallEngine = "java";
    private boolean deepUnpack = false;     // 深度脱壳：固定结构 dump 时静态解析全部 code-item
    private boolean methodTriggerEnabled = false; // 方法级触发，默认关闭
    private int unshellMode = MODE_FIXED;  // 脱壳模式，默认固定结构映射
    private WeakReference<Context> UIContextRef = null;
    protected static com.zitan.cdumpdex.Log log;
    private SharedPreferences configSharedPreferences;
    private Handler mainHandler; // 延迟初始化，避免在 Zygote 进程中创建

    /** ART 在不同版本/厂商上可能返回 long[]、Long 或 Number[]；统一成 native 可用的 cookie 数组。 */
    private static long[] normalizeCookies(Object cookieObject) {
        if (cookieObject instanceof long[]) return (long[]) cookieObject;
        if (cookieObject instanceof Long) return new long[]{(Long) cookieObject};
        if (cookieObject instanceof Number) return new long[]{((Number) cookieObject).longValue()};
        if (cookieObject instanceof Long[]) {
            Long[] values = (Long[]) cookieObject;
            long[] result = new long[values.length];
            for (int i = 0; i < values.length; i++) result[i] = values[i] == null ? 0L : values[i];
            return result;
        }
        if (cookieObject instanceof int[]) {
            int[] values = (int[]) cookieObject;
            long[] result = new long[values.length];
            for (int i = 0; i < values.length; i++) result[i] = Integer.toUnsignedLong(values[i]);
            return result;
        }
        return null;
    }

    private static int cookieStartIndex(Object cookieObject, long[] cookies) {
        // ART 的 long[] cookie 通常以 oat 指针开头；标量 cookie 本身就是 DexFile 指针。
        return cookieObject instanceof long[] && cookies.length > 1 ? 1 : 0;
    }

    private void rememberClassLoaderChain(ClassLoader loader, String source) {
        Set<ClassLoader> visited = new HashSet<>();
        ClassLoader current = loader;
        int depth = 0;
        while (current != null && depth++ < 32 && visited.add(current)) {
            Log.d(DIAG_TAG, "ClassLoader source=" + source + " class="
                    + current.getClass().getName() + " loader=" + current);
            if (current instanceof BaseDexClassLoader) {
                BaseDexClassLoader baseLoader = (BaseDexClassLoader) current;
                synchronized (allClassLoader) {
                    if (!allClassLoader.contains(baseLoader)) allClassLoader.add(baseLoader);
                }
            }
            try {
                current = current.getParent();
            } catch (Throwable error) {
                Log.w(DIAG_TAG, "ClassLoader parent unavailable class="
                        + current.getClass().getName(), error);
                break;
            }
        }
    }

    /**
     * 获取 UIContext（从 WeakReference 中安全获取）
     */
    private Context getUIContext() {
        return UIContextRef != null ? UIContextRef.get() : null;
    }

    /**
     * 设置 UIContext（使用 WeakReference 避免内存泄漏）
     */
    private void setUIContext(Context context) {
        UIContextRef = context != null ? new WeakReference<>(context) : null;
    }

    // JNI 方法声明
    private native boolean writeDexToFile(long cookie, String absolutePath);
    private native String[] getDexClassNames(long cookie);
    private native boolean dumpDexMethodCodeItems(long cookie, String absolutePath);
    private native void registerDexFileOutput(long cookie, String absolutePath);
    private native int dumpDexByMemoryScan(String outputDir);

    private static final class MethodTriggerBudget {
        final long perMethodTimeoutMs = 300L;
        final long totalTimeoutMs = 10000L;
        final int maxFailures = 20;
    }

    /**
     * 受限方法触发器：只处理无参、非 native/abstract/synthetic 方法。
     * 目的仅是给运行时回填 code_item 的机会，不是通用反射执行器。
     */
    private static final class SafeMethodTrigger {
        private static final class CallRuleSet {
            private final List<String> includes = new ArrayList<>();
            private final List<String> excludes = new ArrayList<>();

            static CallRuleSet load(File file) {
                CallRuleSet result = new CallRuleSet();
                if (file == null || !file.isFile()) return result;
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String rule = line.trim();
                        if (rule.isEmpty() || rule.startsWith("#")) continue;
                        boolean excluded = rule.charAt(0) == '!';
                        if (excluded) rule = rule.substring(1).trim();
                        rule = normalize(rule);
                        if (rule == null || rule.isEmpty()) continue;
                        (excluded ? result.excludes : result.includes).add(rule);
                    }
                } catch (Throwable ignored) {}
                return result;
            }

            boolean matches(String className) {
                String name = normalize(className);
                if (name == null || includes.isEmpty()) return false;
                for (String rule : excludes) if (matchesRule(name, rule)) return false;
                for (String rule : includes) if (matchesRule(name, rule)) return true;
                return false;
            }

            private static boolean matchesRule(String className, String rule) {
                if (rule.endsWith("*")) return className.startsWith(rule.substring(0, rule.length() - 1));
                return className.equals(rule);
            }

            private static String normalize(String value) {
                if (value == null) return null;
                String result = value.trim();
                if (result.startsWith("L") && result.endsWith(";")) result = result.substring(1, result.length() - 1);
                return result.replace('/', '.');
            }
        }

        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final Set<String> invoked = new HashSet<>();
        private int failures;

        int trigger(ClassLoader loader, String[] classNames, File rulesFile, File reportFile) {
            if (loader == null || classNames == null || reportFile == null) return 0;
            MethodTriggerBudget budget = new MethodTriggerBudget();
            CallRuleSet rules = CallRuleSet.load(rulesFile);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget.totalTimeoutMs);
            int classes = 0;
            int methods = 0;
            try (BufferedWriter report = new BufferedWriter(new FileWriter(reportFile, false))) {
                report.write("method-trigger mode=safe\n");
                report.write("rules=" + (rulesFile == null ? "<none>" : rulesFile.getAbsolutePath())
                        + " per_method_ms=" + budget.perMethodTimeoutMs + " total_ms=" + budget.totalTimeoutMs + "\n");
                for (String rawName : classNames) {
                    if (stopped.get() || System.nanoTime() >= deadline || failures >= budget.maxFailures) break;
                    String name = normalizeClassName(rawName);
                    if (name == null || isDangerousName(name) || !rules.matches(name)) {
                        report.write("SKIP class=" + name + " reason=rule\n");
                        continue;
                    }
                    Class<?> clazz;
                    try {
                        clazz = Class.forName(name, false, loader);
                    } catch (Throwable e) {
                        failures++;
                        report.write("FAIL class=" + name + " reason=load " + safeMessage(e) + "\n");
                        continue;
                    }
                    classes++;
                    Object receiver = null;
                    if (!Modifier.isAbstract(clazz.getModifiers()) && !clazz.isInterface()) {
                        receiver = createReceiver(clazz, report);
                    } else {
                        receiver = findConcreteReceiver(clazz, loader, classNames, report);
                    }
                    Method[] declared;
                    try {
                        declared = clazz.getDeclaredMethods();
                    } catch (Throwable e) {
                        failures++;
                        report.write("FAIL class=" + name + " reason=methods " + safeMessage(e) + "\n");
                        continue;
                    }
                    for (Method method : declared) {
                        if (stopped.get() || failures >= budget.maxFailures
                                || System.nanoTime() >= deadline) break;
                        if (!isCandidate(method, receiver)) continue;
                        String key = method.toGenericString();
                        if (!invoked.add(key)) continue;
                        methods++;
                        Throwable error = invokeWithTimeout(method, receiver, budget.perMethodTimeoutMs);
                        if (error == null) {
                            report.write("OK " + key + "\n");
                        } else {
                            failures++;
                            report.write((error instanceof TimeoutException ? "TIMEOUT " : "FAIL ")
                                    + key + " reason=" + safeMessage(error) + "\n");
                            if (error instanceof TimeoutException) stopped.set(true);
                        }
                    }
                    report.flush();
                }
                report.write("summary classes=" + classes + " methods=" + methods + " failures=" + failures
                        + " stopped=" + stopped.get() + "\n");
            } catch (Throwable ignored) {
                stopped.set(true);
            } finally {
                executor.shutdownNow();
            }
            return methods;
        }

        private static String normalizeClassName(String name) {
            if (name == null || name.length() < 3) return null;
            String result = name;
            if (result.charAt(0) == 'L' && result.endsWith(";")) result = result.substring(1, result.length() - 1);
            return result.replace('/', '.');
        }

        private static boolean isDangerousName(String name) {
            return name.startsWith("java.") || name.startsWith("android.") || name.startsWith("kotlin.")
                    || name.startsWith("com.zitan.cdumpdex.");
        }

        private static boolean isCandidate(Method method, Object receiver) {
            int modifiers = method.getModifiers();
            if (method.getParameterTypes().length > 8 || buildArguments(method.getParameterTypes()) == null
                    || Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)
                    || method.isSynthetic()) return false;
            return Modifier.isStatic(modifiers) || receiver != null;
        }

        private Object createReceiver(Class<?> clazz, BufferedWriter report) {
            Future<Object> future = executor.submit(() -> {
                Constructor<?> constructor = selectConstructor(clazz);
                if (constructor == null) throw new NoSuchMethodException("no supported constructor");
                if (!Modifier.isPublic(constructor.getModifiers())) constructor.setAccessible(true);
                return constructor.newInstance(buildArguments(constructor.getParameterTypes()));
            });
            try {
                return future.get(300L, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                stopped.set(true);
                try { report.write("TIMEOUT class=" + clazz.getName() + " reason=constructor\n"); }
                catch (IOException ignored) {}
                return null;
            } catch (Throwable e) {
                future.cancel(true);
                try { report.write("INFO class=" + clazz.getName() + " receiver=unavailable reason=" + safeMessage(e) + "\n"); }
                catch (IOException ignored) {}
                return null;
            }
        }

        private static Constructor<?> selectConstructor(Class<?> clazz) {
            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            Constructor<?> selected = null;
            for (Constructor<?> constructor : constructors) {
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length > 8 || buildArguments(parameterTypes) == null) continue;
                if (selected == null || parameterTypes.length < selected.getParameterTypes().length) selected = constructor;
            }
            return selected;
        }

        private Object findConcreteReceiver(Class<?> abstractType, ClassLoader loader,
                                            String[] classNames, BufferedWriter report) {
            for (String rawName : classNames) {
                String name = normalizeClassName(rawName);
                if (name == null || isDangerousName(name)) continue;
                try {
                    Class<?> candidate = Class.forName(name, false, loader);
                    int modifiers = candidate.getModifiers();
                    if (!abstractType.isAssignableFrom(candidate) || Modifier.isAbstract(modifiers)
                            || candidate.isInterface()) continue;
                    Object receiver = createReceiver(candidate, report);
                    if (receiver != null) return receiver;
                } catch (Throwable ignored) {}
            }
            return null;
        }

        private Throwable invokeWithTimeout(Method method, Object receiver, long timeoutMs) {
            Future<?> future = executor.submit(() -> {
                try {
                    if (!method.isAccessible()) method.setAccessible(true);
                    method.invoke(receiver, buildArguments(method.getParameterTypes()));
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS);
                return null;
            } catch (TimeoutException e) {
                future.cancel(true);
                return e;
            } catch (Throwable e) {
                future.cancel(true);
                return e.getCause() == null ? e : e.getCause();
            }
        }

        /** 仅生成无副作用的占位参数；引用类型统一使用 null，避免递归创建对象图。 */
        private static Object[] buildArguments(Class<?>[] parameterTypes) {
            if (parameterTypes == null) return null;
            Object[] args = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> type = parameterTypes[i];
                if (type == null || type == Void.TYPE) return null;
                if (!type.isPrimitive()) {
                    args[i] = type == String.class ? "" : null;
                } else if (type == Boolean.TYPE) {
                    args[i] = false;
                } else if (type == Character.TYPE) {
                    args[i] = '\0';
                } else if (type == Byte.TYPE) {
                    args[i] = (byte) 0;
                } else if (type == Short.TYPE) {
                    args[i] = (short) 0;
                } else if (type == Integer.TYPE) {
                    args[i] = 0;
                } else if (type == Long.TYPE) {
                    args[i] = 0L;
                } else if (type == Float.TYPE) {
                    args[i] = 0F;
                } else if (type == Double.TYPE) {
                    args[i] = 0D;
                } else {
                    return null;
                }
            }
            return args;
        }

        private static String safeMessage(Throwable e) {
            String text = e == null ? "unknown" : e.toString();
            return text.replace('\n', ' ').replace('\r', ' ');
        }
    }

    // LoadClass Hook JNI 方法
    private native void setHookOutputDir(String outputDir);
    private native boolean installLoadClassHook();
    private native void uninstallLoadClassHook();
    private native int getDumpedDexCount();
    private native void resetDumpCount();
    private native boolean isLoadClassHookActive();
    private native int getApiLevel();
    private native String listLoadClassSymbols();

    static {
        System.loadLibrary("cdumpdex");
        System.loadLibrary("dexkit");
    }

    /**
     * 获取主线程 Handler，延迟初始化
     */
    private Handler getMainHandler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }

    public void dumpClassLoader(BaseDexClassLoader targetClassLoader, File dexDumpFile) throws Throwable {
        Log.i(DIAG_TAG, "dumpClassLoader mode=" + unshellMode + " loader=" + targetClassLoader
                + " output=" + dexDumpFile);
        // 根据脱壳模式选择不同的实现
        switch (unshellMode) {
            case MODE_MEMORY_SCAN:
                dumpByMemoryScan(dexDumpFile);
                break;
            case MODE_LOADCLASS_HOOK:
                // LoadClass Hook 模式在 Application.attach 时已经安装
                // 这里只显示当前 dump 的状态
                log.d("LoadClass Hook 模式: 已 dump " + getDumpedDexCount() + " 个 DEX");
                break;
            case MODE_ROOT_MEMORY:
                // Root内存扫描模式由浮动菜单中的专用按钮触发
                log.d("Root内存扫描模式: 请从菜单选择\"Root内存脱壳\"");
                break;
            case MODE_FIXED:
            default:
                dumpByFixedStructure(targetClassLoader, dexDumpFile);
                break;
        }
    }

    /**
     * 模式1: 固定结构映射 (默认)
     */
    private void dumpByFixedStructure(BaseDexClassLoader targetClassLoader, File dexDumpFile) throws Throwable {
        dumpByFixedStructure(targetClassLoader, dexDumpFile, false);
    }

    /**
     * 模式1: 固定结构映射 (默认)
     * @param early true = 早时机模式(InMemoryDexClassLoader 构造瞬间):
     *              只做静态 cookie 直读，避免过早执行耗时解析。
     */
    private void dumpByFixedStructure(BaseDexClassLoader targetClassLoader, File dexDumpFile, boolean early) throws Throwable {
        Log.d(DIAG_TAG, "Fixed dump start loader=" + targetClassLoader
                + " output=" + dexDumpFile + " early=" + early);
        File logFile = new File(dexDumpFile, "log.txt");
        try {
            ToolClass.createFile(logFile);
        } catch (Throwable ignored) {}
        try (BufferedWriter logWriter = new BufferedWriter(new FileWriter(logFile))) {
            logWriter.write(targetClassLoader.toString());
            logWriter.flush();
        } catch (Exception e) {
            log.d("写入日志文件失败: " + e.getMessage());
        }

        Object[] dexElements;
        try {
            Object pathList = ReflectUtils.getPathList(targetClassLoader);
            dexElements = ReflectUtils.getDexElements(pathList);
        } catch (Throwable error) {
            Log.e(DIAG_TAG, "Fixed dump pathList/dexElements failed loader=" + targetClassLoader, error);
            return;
        }
        Log.d(DIAG_TAG, "Fixed dump dexElements=" + (dexElements == null ? -1 : dexElements.length));
        if (dexElements == null) {
            return;
        }
        int attempted = 0;
        int succeeded = 0;
        int failed = 0;
        File loadedClassFile = new File(dexDumpFile, "loadedClass.txt");
        try {
            ToolClass.createFile(loadedClassFile);
        } catch (Throwable ignored) {}
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(loadedClassFile))) {
            for (Object dexElement : dexElements) {
                DexFile dexFile;
                try {
                    dexFile = ReflectUtils.getDexFile(dexElement);
                } catch (Throwable error) {
                    failed++;
                    Log.w(DIAG_TAG, "DexElement dexFile read failed element=" + dexElement, error);
                    continue;
                }
                if (dexFile != null) {
                    Object cookieObject;
                    try {
                        cookieObject = ReflectUtils.getMCookie(dexFile);
                    } catch (Throwable error) {
                        failed++;
                        Log.w(DIAG_TAG, "DexFile cookie read failed dexFile=" + dexFile, error);
                        continue;
                    }
                    long[] mCookie = normalizeCookies(cookieObject);
                    if (mCookie == null) {
                        Log.w(DIAG_TAG, "Unsupported cookie type dexFile=" + dexFile
                                + " type=" + (cookieObject == null ? "null" : cookieObject.getClass().getName()));
                        continue;
                    }
                    Log.d(DIAG_TAG, "DexFile=" + dexFile + " cookieCount=" + mCookie.length);

                    int cookieStart = cookieStartIndex(cookieObject, mCookie);
                    for (int i = cookieStart; i < mCookie.length; i++) {
                        long cookie = mCookie[i];
                        if (cookie == 0) {
                            continue;
                        }
                        File dexFileObj = new File(dexDumpFile, "cookie_" + Long.toHexString(cookie) + ".dex");
                        registerDexFileOutput(cookie, dexFileObj.getAbsolutePath());
                    }

                    if (!early) {
                        if (deepUnpack) {
                            for (int ci = cookieStart; ci < mCookie.length; ci++) {
                                long codeCookie = mCookie[ci];
                                if (codeCookie == 0) continue;
                                File codeItemFile = new File(dexDumpFile,
                                        "cookie_" + Long.toHexString(codeCookie) + "_code_items.txt");
                                try {
                                    if (!dumpDexMethodCodeItems(codeCookie, codeItemFile.getAbsolutePath())) {
                                        Log.w(DIAG_TAG, "Code-item dump failed cookie=0x"
                                                + Long.toHexString(codeCookie));
                                    }
                                } catch (Throwable codeError) {
                                    Log.w(DIAG_TAG, "Code-item dump exception cookie=0x"
                                            + Long.toHexString(codeCookie), codeError);
                                }
                            }
                        }
                        String[] nameList;
                        try {
                            if ("c".equals(activeCallEngine)) {
                                ArrayList<String> cNames = new ArrayList<>();
                                for (int ci = cookieStart; ci < mCookie.length; ci++) {
                                    long classCookie = mCookie[ci];
                                    if (classCookie == 0) continue;
                                    String[] parsed = getDexClassNames(classCookie);
                                    if (parsed != null) {
                                        for (String className : parsed) {
                                            if (className != null && !cNames.contains(className)) cNames.add(className);
                                        }
                                    }
                                }
                                nameList = cNames.toArray(new String[0]);
                            } else {
                                nameList = ReflectUtils.getNameList(cookieObject);
                            }
                        } catch (Throwable error) {
                            nameList = null;
                            Log.w(DIAG_TAG, "DexFile class list unavailable dexFile=" + dexFile, error);
                        }
                        if (nameList != null) {
                            if (enablePattern) {
                                for (String name : nameList) {
                                    boolean skip = false;
                                    writer.write("Load -> " + name);
                                    writer.flush();
                                    for (String rule : blackList) {
                                        Pattern pattern = Pattern.compile(rule);
                                        Matcher matcher = pattern.matcher(name);
                                        if (matcher.matches()) {
                                            skip = true;
                                            writer.write(" -> Skip\n");
                                            writer.flush();
                                        }
                                    }
                                    if (skip) {
                                        continue;
                                    }
                                    // 根据配置决定是否主动loadClass
                                    if (activeLoadClass) {
                                        try {
                                            targetClassLoader.loadClass(name);
                                            writer.write(" -> Success\n");
                                            writer.flush();
                                        } catch (Throwable e) {
                                            writer.write(" -> Fail: " + e.getMessage() + "\n");
                                            writer.flush();
                                        }
                                    } else {
                                        writer.write(" -> Skip (activeLoadClass disabled)\n");
                                        writer.flush();
                                    }
                                }
                            } else {
                                for (String name : nameList) {
                                    writer.write("Load -> " + name);
                                    writer.flush();
                                    if (blackList.contains(name)) {
                                        writer.write(" -> Skip\n");
                                        writer.flush();
                                        continue;
                                    }
                                    // 根据配置决定是否主动loadClass
                                    if (activeLoadClass) {
                                        try {
                                            targetClassLoader.loadClass(name);
                                            writer.write(" -> Success\n");
                                            writer.flush();
                                        } catch (Throwable e) {
                                            writer.write(" -> Fail: " + e.getMessage() + "\n");
                                            writer.flush();
                                        }
                                    } else {
                                        writer.write(" -> Skip (activeLoadClass disabled)\n");
                                        writer.flush();
                                    }
                                }
                            }
                        }
                        if (deepUnpack && methodTriggerEnabled && nameList != null && cookieStart < mCookie.length) {
                            File triggerReport = new File(dexDumpFile,
                                        "cookie_" + Long.toHexString(mCookie[cookieStart]) + "_method_trigger.txt");
                            try {
                                File rulesFile = getActiveCallRulesFile();
                                int triggered = new SafeMethodTrigger().trigger(targetClassLoader, nameList, rulesFile, triggerReport);
                                Log.i(DIAG_TAG, "Method trigger complete cookie=0x"
                                        + Long.toHexString(mCookie[cookieStart]) + " methods=" + triggered);
                            } catch (Throwable triggerError) {
                                Log.w(DIAG_TAG, "Method trigger failed", triggerError);
                            }
                            for (int ci = cookieStart; ci < mCookie.length; ci++) {
                                long triggeredCookie = mCookie[ci];
                                if (triggeredCookie == 0) continue;
                                File afterTriggerFile = new File(dexDumpFile,
                                        "cookie_" + Long.toHexString(triggeredCookie) + "_code_items_after_trigger.txt");
                                try {
                                    dumpDexMethodCodeItems(triggeredCookie, afterTriggerFile.getAbsolutePath());
                                } catch (Throwable afterError) {
                                    Log.w(DIAG_TAG, "Post-trigger code-item snapshot failed cookie=0x"
                                            + Long.toHexString(triggeredCookie), afterError);
                                }
                            }
                        }
                    }

                    for (int i = cookieStart; i < mCookie.length; i++) {
                        long cookie = mCookie[i];
                        if (cookie == 0) {
                            continue;
                        }
                        attempted++;
                        File dexFileObj = new File(dexDumpFile, "cookie_" + Long.toHexString(cookie) + ".dex");
                        // 增强: writeDexToFile 已支持头重建(magic 被清零也能还原),
                        // 若仍失败再尝试一次(壳守护线程可能恰在清零窗口)
                        if (!writeDexToFile(cookie, dexFileObj.getAbsolutePath())) {
                            failed++;
                            log.d("cookie为: " + Long.toHexString(cookie) + " 的文件dump失败");
                            Log.w(DIAG_TAG, "Cookie dump failed ref=0x" + Long.toHexString(cookie)
                                    + " path=" + dexFileObj.getAbsolutePath());
                        } else {
                            succeeded++;
                            Log.d(DIAG_TAG, "Cookie dump success ref=0x" + Long.toHexString(cookie)
                                    + " bytes=" + dexFileObj.length());
                        }
                    }
                }
            }
            log.d("DEX导出完成: 尝试" + attempted + "个, 成功" + succeeded + "个, 失败" + failed + "个");
            Log.d(DIAG_TAG, "Fixed dump complete attempted=" + attempted
                    + " succeeded=" + succeeded + " failed=" + failed);
        }
    }

    /**
     * 模式2: 内存特征匹配
     */
    private void dumpByMemoryScan(File dexDumpFile) {
        try {
            log.d("开始内存特征匹配模式");
            int count = dumpDexByMemoryScan(dexDumpFile.getAbsolutePath());
            log.d("内存扫描完成，找到 " + count + " 个DEX文件");
        } catch (Exception e) {
            log.d("内存扫描失败: " + Log.getStackTraceString(e));
        }
    }

    public void injectDex(BaseDexClassLoader classLoader) throws Throwable {
        File[] dexList = dexInjectPathFile.listFiles();
        if (dexList != null && dexList.length > 0) {
            Object pathList = ReflectUtils.getPathList(classLoader);
            Object[] dexElements = ReflectUtils.getDexElements(pathList);
            Class<?> elementClass = dexElements[0].getClass();
            Object[] newElements = (Object[]) Array.newInstance(elementClass, dexElements.length + dexList.length);
            for (int i = 0; i < dexList.length; i++) {
                File dex = dexList[i];
                if (!dex.getAbsolutePath().endsWith(".dex")) {
                    continue;
                }
                log.d("热修复加载:" + dex.getAbsolutePath() + "->" + classLoader);
                DexFile dexFile = new DexFile(dex);
                Object additionElement = elementClass.getConstructor(DexFile.class, File.class).newInstance(dexFile, null);
                log.d("构造DexElement:" + dex.getAbsolutePath() + "->" + dexFile);
                newElements[i] = additionElement;
            }

            System.arraycopy(dexElements, 0, newElements, dexList.length, dexElements.length);
            ReflectUtils.setDexElements(pathList, newElements);
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    public void injectSo(BaseDexClassLoader classLoader) throws Throwable {
        Object pathList = ReflectUtils.getPathList(classLoader);
        List<File> nativeLibraryDirectories = ReflectUtils.getNativeLibraryDirectories(pathList);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            nativeLibraryDirectories.addFirst(soInjectPathFile);
        } else {
            nativeLibraryDirectories.add(0, soInjectPathFile);
        }

        Set<String> soLoadList = configSharedPreferences.getStringSet("soNeededLoad", new HashSet<>());
        if (!soLoadList.isEmpty()) {
            for (String soName : soLoadList) {
                log.d("加载so文件:" + soName);
                File soFile = new File(soInjectPathFile, soName);
                System.load(soFile.getAbsolutePath());
            }
        }
    }

    public void inject(Context context) throws Throwable {
        // 路径初始化已移至 Application.attach hook 中
        // 此方法保留用于兼容性，实际初始化在 hook 中完成
    }

    /**
     * 从配置文件读取配置
     */
    private void loadConfigFromFile(Context context) {
        try {
            String targetPackage = context.getPackageName();
            // 增强: 多路径 fallback —— Android 11+ scoped storage 下
            // /storage/emulated/0/Android/data/<pkg>/files/ 可能无法由外部工具写入,
            // 增加 /sdcard 根目录、模块私有目录等常见路径。
            File[] configCandidates = new File[]{
                new File("/storage/emulated/0/Android/data/" + targetPackage + "/files/cdumpdex_config.json"),
                new File("/storage/emulated/0/cdumpdex_config.json"),
                new File("/sdcard/cdumpdex_config.json"),
                new File("/storage/emulated/0/Download/cdumpdex_config.json"),
                new File("/data/local/tmp/cdumpdex_config.json"),
            };

            File configFile = null;
            for (File candidate : configCandidates) {
                if (candidate.exists() && candidate.isFile()) {
                    configFile = candidate;
                    break;
                }
            }

            if (configFile == null) {
                StringBuilder sb = new StringBuilder("配置文件不存在, 已检查路径: ");
                for (File candidate : configCandidates) {
                    sb.append(candidate.getAbsolutePath()).append("(")
                      .append(candidate.exists() ? "存在" : "不存在").append(") ");
                }
                log.d(sb.toString());
                return;
            }

            {
                BufferedReader reader = new BufferedReader(new FileReader(configFile));
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
                reader.close();

                String configContent = json.toString();
                log.d("读取到配置文件(" + configFile.getAbsolutePath() + "): " + configContent);

                // 解析 JSON 配置
                // 格式: {"unshell_mode":"fixed","active_load_class":true}

                // 解析脱壳模式
                if (configContent.contains("\"unshell_mode\":\"memory_scan\"")) {
                    unshellMode = MODE_MEMORY_SCAN;
                    log.d("脱壳模式: 内存特征匹配");
                } else if (configContent.contains("\"unshell_mode\":\"loadclass_hook\"")) {
                    unshellMode = MODE_LOADCLASS_HOOK;
                    log.d("脱壳模式: LoadClass Hook");
                } else if (configContent.contains("\"unshell_mode\":\"root_memory\"")) {
                    unshellMode = MODE_ROOT_MEMORY;
                    log.d("脱壳模式: Root进程内存扫描");
                } else {
                    unshellMode = MODE_FIXED;
                    log.d("脱壳模式: 固定结构映射");
                }

                // 解析主动调用模式
                if (configContent.contains("\"active_load_class\":false")) {
                    activeLoadClass = false;
                    log.d("主动调用模式: 禁用");
                } else {
                    activeLoadClass = true;
                    log.d("主动调用模式: 启用");
                }

                Pattern enginePattern = Pattern.compile("\"active_call_engine\"\\s*:\\s*\"(java|c)\"");
                Matcher engineMatcher = enginePattern.matcher(configContent);
                activeCallEngine = engineMatcher.find() ? engineMatcher.group(1) : "java";
                log.d("Active call engine: " + activeCallEngine);

                // 解析深度脱壳
                deepUnpack = configContent.contains("\"deep_unpack\":true");
                log.d("深度脱壳: " + (deepUnpack
                        ? "启用（固定结构 dump 时解析全部 code-item）"
                        : "禁用"));
                methodTriggerEnabled = configContent.contains("\"method_trigger_enabled\":true");
                log.d("方法级触发: " + (methodTriggerEnabled ? "启用（受限安全模式）" : "禁用"));
            }
        } catch (Exception e) {
            log.d("读取配置文件失败: " + e.getMessage());
        }
    }

    public void getBlackList() throws IOException {
        Context context = getUIContext();
        if (blackListFile == null && context != null) {
            blackListFile = new File(Objects.requireNonNull(context.getExternalCacheDir()).getParentFile(), "ztBlackList");
            if (blackListFile.exists()) {
                log.d("检测到黑名单文件，开始读取黑名单配置");
                try (BufferedReader reader = new BufferedReader(new FileReader(blackListFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.equals("enable pattern")) {
                            log.d("检测到启用正则模式");
                            enablePattern = true;
                            continue;
                        }
                        // 匹配smali写法，转为Java写法
                        if (line.startsWith("L") && line.contains("/") && line.endsWith(";")) {
                            line = line.substring(1, line.length() - 1).replace("/", ".");
                        }
                        if (!blackList.contains(line)) {
                            log.d("匹配到类规则:" + line);
                            blackList.add(line);
                        }
                    }
                }
            }
        }
    }

    @Override
    @SuppressLint("SdCardPath")
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (loadPackageParam.packageName.equals("com.zitan.cdumpdex")) {
            return;
        }

        Log.i(DIAG_TAG, "handleLoadPackage package=" + loadPackageParam.packageName
                + " process=" + loadPackageParam.processName
                + " sdk=" + Build.VERSION.SDK_INT
                + " classLoader=" + (loadPackageParam.classLoader == null ? "null"
                : loadPackageParam.classLoader.getClass().getName()));
        rememberClassLoaderChain(loadPackageParam.classLoader, "loadPackage");

        XposedBridge.hookAllConstructors(BaseDexClassLoader.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                try {
                    BaseDexClassLoader cacheClassLoader = (BaseDexClassLoader) param.thisObject;
                    Log.d(DIAG_TAG, "BaseDexClassLoader constructed class="
                            + cacheClassLoader.getClass().getName() + " loader=" + cacheClassLoader);

                    // 只是记录 ClassLoader，不执行耗时操作
                    rememberClassLoaderChain(cacheClassLoader, "constructor");

                    // 增强(早时机 dump): 对 InMemoryDexClassLoader 在构造返回的瞬间立即脱壳。
                    // 某些加固在 InMemoryDexClassLoader 构造完成后会启动后台守护线程
                    // 立即清零 dex 头部, 等到 Application.attach 再 dump 时 magic 已被抹除。
                    // 利用 hookAllConstructors(after) 的时机, 在壳的 native 清零逻辑执行前
                    // 抢先输出 dex —— 这是对抗"头清零"最重要的时间窗口。
                    if (cacheClassLoader != null
                        && cacheClassLoader.getClass().getName().contains("InMemoryDexClassLoader")) {
                        try {
                            if (dexDumpFile == null) {
                                // 早时机无 Context, 用 LoadPackageParam 里的 appInfo.dataDir
                                // (App 私有目录, 进程可写) 定位输出目录
                                File dataDir = loadPackageParam.appInfo != null
                                        ? new File(loadPackageParam.appInfo.dataDir) : null;
                                if (dataDir != null && dataDir.exists()) {
                                    dexDumpFile = new File(dataDir, "dump");
                                } else {
                                    dexDumpFile = new File("/data/local/tmp/cdumpdex", "dump");
                                }
                            }
                            ToolClass.createDirectory(dexDumpFile);
                            File earlyDir = new File(dexDumpFile, "early_mem");
                            ToolClass.createDirectory(earlyDir);
                            dumpByFixedStructure(cacheClassLoader, earlyDir, true);
                            log.d("[早时机] InMemoryDexClassLoader 构造完成, 已抢先 dump 到 " + earlyDir);
                        } catch (Throwable t) {
                            log.d("[早时机] dump 失败(忽略): " + t.getMessage());
                        }
                    }

                    // DEX 注入移到后台线程
                    if (injectDexToAllClassLoader) {
                        final BaseDexClassLoader loaderToInject = cacheClassLoader;
                        new Thread(() -> {
                            try {
                                injectDex(loaderToInject);
                            } catch (Throwable e) {
                                // 忽略注入失败
                            }
                        }).start();
                    }
                } catch (Throwable e) {
                    // 忽略 hook 中的错误，防止影响应用
                }
            }
        });

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                try {
                    Context context = (Context) param.args[0];
                    File logFile = new File(context.getExternalCacheDir().getParentFile(), "ZtLog.txt");
                    log = new com.zitan.cdumpdex.Log(logFile);
                    Log.i(DIAG_TAG, "Application.attach package=" + context.getPackageName()
                            + " process=" + loadPackageParam.processName
                            + " loader=" + context.getClassLoader());
                    rememberClassLoaderChain(context.getClassLoader(), "attach");
                    configSharedPreferences = context.getSharedPreferences("ZtDumpConfig", Context.MODE_PRIVATE);

                    // 初始化路径（快速操作）
                    File dataFile = Objects.requireNonNull(context.getExternalCacheDir()).getParentFile();
                    File externalDumpFile = new File(dataFile, "dump");
                    // 早期 InMemoryDexClassLoader hook 尚无 Context，会先写到私有 dataDir。
                    // attach 拿到外部目录后复制早期结果，后续普通 dump 统一写到可拉取的外部目录。
                    File earlyPrivateDump = dexDumpFile;
                    if (earlyPrivateDump != null && !earlyPrivateDump.equals(externalDumpFile)) {
                        try {
                            ToolClass.copyDirectory(new File(earlyPrivateDump, "early_mem"),
                                    new File(externalDumpFile, "early_mem"));
                            log.d("早期 dump 已复制到外部目录: "
                                    + new File(externalDumpFile, "early_mem").getAbsolutePath());
                        } catch (Throwable copyError) {
                            log.d("早期 dump 复制失败: " + copyError.getMessage());
                        }
                    }
                    dexDumpFile = externalDumpFile;
                    injectPathFile = externalDumpFile;
                    dexInjectPathFile = new File(injectPathFile, "dex");
                    soInjectPathFile = new File(injectPathFile, "so");

                    // 读取配置文件
                    loadConfigFromFile(context);

                    // 添加 native 库路径（可能失败，不影响主流程）
                    try {
                        BaseDexClassLoader targetClassLoader = (BaseDexClassLoader) context.getClassLoader();
                        Object pathList = ReflectUtils.getPathList(targetClassLoader);
                        List<File> nativeLibraryDirectories = ReflectUtils.getNativeLibraryDirectories(pathList);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                            nativeLibraryDirectories.addFirst(soInjectPathFile);
                        } else {
                            nativeLibraryDirectories.add(0, soInjectPathFile);
                        }
                    } catch (Throwable e) {
                        log.d("添加 native 库路径失败: " + e.getMessage());
                    }

                    // 在后台线程执行耗时操作
                    new Thread(() -> {
                        try {
                            BaseDexClassLoader targetClassLoader = (BaseDexClassLoader) context.getClassLoader();

                            // DEX 注入（耗时操作）
                            if (configSharedPreferences.getBoolean("DexInject", false)) {
                                if (configSharedPreferences.getBoolean("InjectApplicationClassLoader", false)) {
                                    injectDex(targetClassLoader);
                                } else if (configSharedPreferences.getBoolean("InjectAllClassLoader", false)) {
                                    injectDex(targetClassLoader);
                                    injectDexToAllClassLoader = true;
                                }
                            }

                            // 如果是 LoadClass Hook 模式，安装 Hook
                            if (unshellMode == MODE_LOADCLASS_HOOK) {
                                installLoadClassHookInternal(context);
                            }

                            // 加载 SO 文件
                            Set<String> soLoadList = configSharedPreferences.getStringSet("soNeededLoad", new HashSet<>());
                            for (String soName : soLoadList) {
                                String soPath = new File(soInjectPathFile, soName).getAbsolutePath();
                                String err = (String) XposedBridge.invokeOriginalMethod(
                                    ReflectUtils.getMethod(Runtime.class, "nativeLoad", String.class, ClassLoader.class, Class.class),
                                    Runtime.getRuntime(), new Object[]{soPath, targetClassLoader, null});
                                log.d(err);
                            }
                        } catch (Throwable e) {
                            log.d("后台初始化失败: " + Log.getStackTraceString(e));
                        }
                    }).start();
                } catch (Throwable e) {
                    // 防止 hook 失败导致应用崩溃
                    android.util.Log.e("cDumpDex", "Application.attach hook 失败", e);
                }
            }
        });

        XposedHelpers.findAndHookMethod(Dialog.class, "show", new XC_MethodHook() {
            @SuppressLint("ClickableViewAccessibility")
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);

                try {
                    //过滤模块创建的Dialog，因为栈信息上面有几条是lsposed的，所以从4开始查。栈中有模块包名证明是模块创建的dialog。
                    //其他xposed框架不清楚会产生多少堆栈信息
                    StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
                    for (int i = 4; i < stackTraceElements.length; i++) {
                        StackTraceElement stackTraceElement = stackTraceElements[i];
                        if (stackTraceElement.getClassName().contains("com.zitan.cdumpdex")) {
                            return;
                        }
                    }
                    Dialog dialog = (Dialog) param.thisObject;
                    Context ctx = dialog.getContext();
                    setUIContext(ctx);
                    configSharedPreferences = ctx.getSharedPreferences("ZtDumpConfig", Context.MODE_PRIVATE);

                    // 安全地添加 ClassLoader
                    try {
                        ClassLoader cl = ctx.getClassLoader();
                        if (cl instanceof BaseDexClassLoader) {
                            synchronized (allClassLoader) {
                                if (!allClassLoader.contains(cl)) {
                                    allClassLoader.add((BaseDexClassLoader) cl);
                                }
                            }
                        }
                        Context appContext = ctx.getApplicationContext();
                        if (appContext != null) {
                            ClassLoader appCl = appContext.getClassLoader();
                            if (appCl instanceof BaseDexClassLoader && !allClassLoader.contains(appCl)) {
                                synchronized (allClassLoader) {
                                    if (!allClassLoader.contains(appCl)) {
                                        allClassLoader.add((BaseDexClassLoader) appCl);
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}

                    if (dialog.getWindow() == null) return;
                    ViewGroup parent = (ViewGroup) dialog.getWindow().getDecorView();
                    if (parent == null || viewGroupList.contains(parent)) {
                        return;
                    }
                    viewGroupList.add(parent);

                    if (dexDumpFile == null && ctx.getExternalCacheDir() != null) {
                        dexDumpFile = new File(ctx.getExternalCacheDir().getParentFile(), "dump");
                        ToolClass.createDirectory(dexDumpFile);
                    }

                    getBlackList();

                    if (configSharedPreferences.getBoolean("showFloat", true)) {
                        injectFloatingIcon(ctx, parent);
                    }
                } catch (Throwable e) {
                    // 忽略错误，防止影响 Dialog 显示
                }
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onKeyDown", int.class, KeyEvent.class, new XC_MethodHook() {
            @SuppressLint("ClickableViewAccessibility")
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                try {
                    Activity activity = (Activity) param.thisObject;
                    if (configSharedPreferences == null) {
                        configSharedPreferences = activity.getSharedPreferences("ZtDumpConfig", Context.MODE_PRIVATE);
                    }

                    int keyCode = (int) param.args[0];
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        if (configSharedPreferences.getBoolean("showFloat", true)) {
                            return;
                        }

                        SharedPreferences.Editor editor = configSharedPreferences.edit();
                        editor.putBoolean("showFloat", true);
                        editor.apply();

                        if (activity.getWindow() != null) {
                            ViewGroup parent = (ViewGroup) activity.getWindow().getDecorView();
                            if (parent != null) {
                                injectFloatingIcon(activity, parent);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @SuppressLint({"ClickableViewAccessibility", "UseCompatLoadingForDrawables"})
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                try {
                    Activity activity = (Activity) param.thisObject;
                    setUIContext(activity);

                    configSharedPreferences = activity.getSharedPreferences("ZtDumpConfig", Context.MODE_PRIVATE);

                    // 安全地添加 ClassLoader
                    try {
                        ClassLoader cl = activity.getClassLoader();
                        if (cl instanceof BaseDexClassLoader) {
                            synchronized (allClassLoader) {
                                if (!allClassLoader.contains(cl)) {
                                    allClassLoader.add((BaseDexClassLoader) cl);
                                }
                            }
                        }
                        Context appContext = activity.getApplicationContext();
                        if (appContext != null) {
                            ClassLoader appCl = appContext.getClassLoader();
                            if (appCl instanceof BaseDexClassLoader) {
                                synchronized (allClassLoader) {
                                    if (!allClassLoader.contains(appCl)) {
                                        allClassLoader.add((BaseDexClassLoader) appCl);
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}

                    if (activity.getWindow() == null) return;
                    ViewGroup parent = (ViewGroup) activity.getWindow().getDecorView();
                    if (parent == null || viewGroupList.contains(parent)) {
                        return;
                    }
                    viewGroupList.add(parent);

                    if (dexDumpFile == null && activity.getExternalCacheDir() != null) {
                        dexDumpFile = new File(activity.getExternalCacheDir().getParentFile(), "dump");
                        if (!dexDumpFile.mkdirs()) {
                            // 目录可能已存在
                        }
                    }

                    getBlackList();

                    if (configSharedPreferences.getBoolean("showFloat", true)) {
                        injectFloatingIcon(activity, parent);
                    }
                } catch (Throwable e) {
                    // 忽略错误，防止影响 Activity 创建
                }
            }
        });
    }

    /**
     * 注入浮动图标到指定的 ViewGroup
     */
    @SuppressLint("ClickableViewAccessibility")
    private void injectFloatingIcon(Context context, ViewGroup parent) {
        Drawable icon = context.getApplicationInfo().loadIcon(context.getPackageManager());
        ImageView iconView = new ImageView(context);
        if (iconView.getParent() != null) {
            ((ViewGroup) iconView.getParent()).removeView(iconView);
        }
        iconView.setId(ImageView.generateViewId());
        iconView.setImageDrawable(icon);

        iconView.setOnTouchListener(new VOnTouchListener(context));

        parent.post(() -> {
            int parentWidth = parent.getWidth();
            int parentHeight = parent.getHeight();

            ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(FLOAT_ICON_SIZE, FLOAT_ICON_SIZE);
            params.leftMargin = (parentWidth - FLOAT_ICON_SIZE) / 2;
            params.topMargin = (parentHeight - FLOAT_ICON_SIZE) / 2;

            parent.addView(iconView, params);
            iconView.bringToFront();
        });
    }

    public class VOnTouchListener implements View.OnTouchListener {
        private int initialX, initialY;
        private float lastTouchX, lastTouchY;
        private final int touchSlop;
        private boolean isDragging = false;

        public VOnTouchListener(Context context) {
            this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) v.getLayoutParams();

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getRawX();
                    lastTouchY = event.getRawY();
                    initialX = params.leftMargin;
                    initialY = params.topMargin;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - lastTouchX;
                    float dy = event.getRawY() - lastTouchY;

                    if (!isDragging && (dx * dx + dy * dy > touchSlop * touchSlop)) {
                        isDragging = true;
                    }

                    if (isDragging) {
                        params.leftMargin = (int) (initialX + (event.getRawX() - lastTouchX));
                        params.topMargin = (int) (initialY + (event.getRawY() - lastTouchY));
                        v.setLayoutParams(params);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        v.performClick();
                        showFunctionMenu();
                    }
                    isDragging = false;
                    return true;
            }
            return false;
        }
    }

    /**
     * 菜单项ID常量
     */
    private static final int MENU_DUMP_APPLICATION = 0;
    private static final int MENU_CURRENT_APPLICATION = 1;
    private static final int MENU_DUMP_SELECTED = 2;
    private static final int MENU_DUMP_ALL = 3;
    private static final int MENU_BLACKLIST = 4;
    private static final int MENU_HOTFIX_CONFIG = 5;
    private static final int MENU_LOAD_SO = 6;
    private static final int MENU_DECOMPILE_CLASS = 7;
    private static final int MENU_CANCEL_FLOAT = 8;
    private static final int MENU_DEXKIT_EXPORT = 9;
    private static final int MENU_MEMORY_SCAN = 10;
    private static final int MENU_TRIGGER_RELOAD = 11;  // 触发壳重新加载
    private static final int MENU_LOADCLASS_HOOK_STATUS = 12;  // LoadClass Hook 状态
    private static final int MENU_REFLECTION = 13;  // 反射工具
    private static final int MENU_ROOT_MEMORY_DUMP = 14;  // Root内存脱壳
    private static final int MENU_ACTIVE_CALL_CONFIG = 15;  // 主动调用规则

    /**
     * 显示功能菜单
     */
    private void showFunctionMenu() {
        Context context = getUIContext();
        if (context == null) return;

        // 动态构建菜单项
        ArrayList<String> menuItems = new ArrayList<>();
        ArrayList<Integer> menuIds = new ArrayList<>();

        // 根据脱壳模式添加不同的菜单项
        if (unshellMode == MODE_MEMORY_SCAN) {
            // 内存搜索模式：显示"遍历内存dump"
            menuItems.add("遍历内存dump");
            menuIds.add(MENU_MEMORY_SCAN);
        } else if (unshellMode == MODE_LOADCLASS_HOOK) {
            // LoadClass Hook 模式：显示状态菜单
            menuItems.add("LoadClass Hook 状态");
            menuIds.add(MENU_LOADCLASS_HOOK_STATUS);
        } else if (unshellMode == MODE_ROOT_MEMORY) {
            // Root内存扫描模式：显示Root脱壳菜单
            menuItems.add("Root内存脱壳");
            menuIds.add(MENU_ROOT_MEMORY_DUMP);
        } else {
            // 固定结构映射模式：显示传统的写出dex菜单
            menuItems.add("写出当前Application类加载器dex");
            menuIds.add(MENU_DUMP_APPLICATION);
            menuItems.add("写出指定类加载器dex");
            menuIds.add(MENU_DUMP_SELECTED);
            menuItems.add("写出所有类加载器dex");
            menuIds.add(MENU_DUMP_ALL);
        }

        // 公共菜单项
        menuItems.add("主动调用配置");
        menuIds.add(MENU_ACTIVE_CALL_CONFIG);
        menuItems.add("当前Application");
        menuIds.add(MENU_CURRENT_APPLICATION);
        menuItems.add("生成黑名单文件");
        menuIds.add(MENU_BLACKLIST);
        menuItems.add("热修复配置");
        menuIds.add(MENU_HOTFIX_CONFIG);
        menuItems.add("主动加载指定so");
        menuIds.add(MENU_LOAD_SO);
        menuItems.add("反编译指定类");
        menuIds.add(MENU_DECOMPILE_CLASS);
        menuItems.add("触发壳重新加载");
        menuIds.add(MENU_TRIGGER_RELOAD);
        menuItems.add("取消弹窗显示");
        menuIds.add(MENU_CANCEL_FLOAT);
        menuItems.add("使用dexkit导出dex");
        menuIds.add(MENU_DEXKIT_EXPORT);
        menuItems.add("反射工具");
        menuIds.add(MENU_REFLECTION);

        CharSequence[] menuArray = menuItems.toArray(new CharSequence[0]);

        AlertDialog.Builder functionDialog = new AlertDialog.Builder(context);
        functionDialog.setTitle("菜单");
        functionDialog.setItems(menuArray, (dialog, which) -> {
            int menuId = menuIds.get(which);
            handleMenuClick(menuId);
        });
        functionDialog.create().show();
    }

    /**
     * 处理菜单点击
     */
    private void handleMenuClick(int menuId) {
        switch (menuId) {
            case MENU_DUMP_APPLICATION:
                dumpApplicationClassLoader();
                break;
            case MENU_CURRENT_APPLICATION:
                showCurrentApplication();
                break;
            case MENU_DUMP_SELECTED:
                dumpSelectedClassLoader();
                break;
            case MENU_DUMP_ALL:
                dumpAllClassLoaders();
                break;
            case MENU_BLACKLIST:
                createBlacklistFile();
                break;
            case MENU_HOTFIX_CONFIG:
                showHotFixConfig();
                break;
            case MENU_LOAD_SO:
                showLoadSoDialog();
                break;
            case MENU_DECOMPILE_CLASS:
                showDecompileClassDialog();
                break;
            case MENU_TRIGGER_RELOAD:
                showTriggerReloadDialog();
                break;
            case MENU_CANCEL_FLOAT:
                cancelFloatWindow();
                break;
            case MENU_DEXKIT_EXPORT:
                exportWithDexKit();
                break;
            case MENU_MEMORY_SCAN:
                performMemoryScanDump();
                break;
            case MENU_LOADCLASS_HOOK_STATUS:
                showLoadClassHookStatus();
                break;
            case MENU_REFLECTION:
                showReflectionMenu();
                break;
            case MENU_ROOT_MEMORY_DUMP:
                performRootMemoryDump();
                break;
            case MENU_ACTIVE_CALL_CONFIG:
                showActiveCallConfigDialog();
                break;
        }
    }

    private File getActiveCallRulesFile() {
        File base = dexDumpFile;
        if (base != null && base.getParentFile() != null) {
            return new File(base.getParentFile(), "active_call_rules.txt");
        }
        Context context = getUIContext();
        if (context != null && context.getExternalCacheDir() != null) {
            File parent = context.getExternalCacheDir().getParentFile();
            if (parent != null) return new File(parent, "active_call_rules.txt");
        }
        return context == null ? null : new File(context.getFilesDir(), "active_call_rules.txt");
    }

    private void showActiveCallConfigDialog() {
        Context context = getUIContext();
        File rulesFile = getActiveCallRulesFile();
        if (context == null || rulesFile == null) return;
        try {
            File parent = rulesFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (!rulesFile.exists()) {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(rulesFile))) {
                    writer.write("# 每行一个类规则，空行和 # 开头的行会被忽略\n");
                    writer.write("# com.abc.iii       精确匹配一个类\n");
                    writer.write("# com.*             匹配所有以 com. 开头的类\n");
                    writer.write("# !com.abc.ooo      排除一个类，排除规则优先\n");
                    writer.write("# !com.abc.*        排除所有以 com.abc. 开头的类\n");
                }
            }
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(rulesFile))) {
                String line;
                while ((line = reader.readLine()) != null) content.append(line).append('\n');
            }
            EditText editor = new EditText(context);
            editor.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
            editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            editor.setMinLines(12);
            editor.setMaxLines(24);
            editor.setText(content.toString());
            editor.setSelection(editor.length());

            ScrollView scroll = new ScrollView(context);
            int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
            scroll.setPadding(padding, 0, padding, 0);
            scroll.addView(editor, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            new AlertDialog.Builder(context)
                    .setTitle("主动调用配置")
                    .setMessage("只调用匹配规则的类；排除规则优先。保存后下一次深度脱壳 dump 生效。\n文件: " + rulesFile.getAbsolutePath())
                    .setView(scroll)
                    .setPositiveButton("保存", (dialog, which) -> {
                        try (BufferedWriter writer = new BufferedWriter(new FileWriter(rulesFile, false))) {
                            writer.write(editor.getText().toString());
                            Toast.makeText(context, "主动调用配置已保存", Toast.LENGTH_SHORT).show();
                        } catch (Throwable error) {
                            Toast.makeText(context, "保存失败: " + error.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable error) {
            Toast.makeText(context, "打开主动调用配置失败: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 显示触发重新加载的对话框
     */
    private void showTriggerReloadDialog() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(getUIContext());
        dialog.setTitle("触发壳重新加载");

        CharSequence[] options = {
            "尝试调用Application.attach()",
            "尝试调用Application.onCreate()",
            "尝试重置Application状态并调用attach",
            "搜索并调用壳的初始化方法",
            "导出当前DEX后尝试"
        };

        dialog.setItems(options, (d, which) -> {
            switch (which) {
                case 0:
                    triggerAttach();
                    break;
                case 1:
                    triggerOnCreate();
                    break;
                case 2:
                    resetAndTriggerAttach();
                    break;
                case 3:
                    searchAndTriggerShellInit();
                    break;
                case 4:
                    dumpThenTrigger();
                    break;
            }
        });
        dialog.setNegativeButton("取消", null);
        dialog.create().show();
    }

    /**
     * 尝试调用 Application.attach()
     */
    private void triggerAttach() {
        new Thread(() -> {
            try {
                Application app = (Application) getUIContext().getApplicationContext();
                log.d("尝试调用 " + app.getClass().getName() + ".attach()");

                // 记录调用前的类加载器状态
                BaseDexClassLoader beforeLoader = (BaseDexClassLoader) app.getClassLoader();
                int beforeCount = getClassLoaderDexCount(beforeLoader);
                log.d("调用前 DEX 数量: " + beforeCount);

                // 尝试调用 attach
                java.lang.reflect.Method attachMethod = Application.class.getDeclaredMethod("attach", Context.class);
                attachMethod.setAccessible(true);
                attachMethod.invoke(app, getUIContext());

                // 检查调用后的状态
                int afterCount = getClassLoaderDexCount(beforeLoader);
                log.d("调用后 DEX 数量: " + afterCount);

                getMainHandler().post(() -> {
                    String msg = "attach() 调用完成\n调用前 DEX: " + beforeCount + "\n调用后 DEX: " + afterCount;
                    if (afterCount > beforeCount) {
                        msg += "\n检测到新 DEX！建议立即脱壳";
                    }
                    Toast.makeText(getUIContext(), msg, Toast.LENGTH_LONG).show();
                });

            } catch (Exception e) {
                log.d("attach() 调用失败: " + Log.getStackTraceString(e));
                getMainHandler().post(() -> {
                    Toast.makeText(getUIContext(), "attach() 失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * 尝试调用 Application.onCreate()
     */
    private void triggerOnCreate() {
        new Thread(() -> {
            try {
                Application app = (Application) getUIContext().getApplicationContext();
                log.d("尝试调用 " + app.getClass().getName() + ".onCreate()");

                BaseDexClassLoader beforeLoader = (BaseDexClassLoader) app.getClassLoader();
                int beforeCount = getClassLoaderDexCount(beforeLoader);
                log.d("调用前 DEX 数量: " + beforeCount);

                // 调用 onCreate
                java.lang.reflect.Method onCreateMethod = Application.class.getDeclaredMethod("onCreate");
                onCreateMethod.setAccessible(true);
                onCreateMethod.invoke(app);

                int afterCount = getClassLoaderDexCount(beforeLoader);
                log.d("调用后 DEX 数量: " + afterCount);

                getMainHandler().post(() -> {
                    String msg = "onCreate() 调用完成\n调用前 DEX: " + beforeCount + "\n调用后 DEX: " + afterCount;
                    if (afterCount > beforeCount) {
                        msg += "\n检测到新 DEX！建议立即脱壳";
                    }
                    Toast.makeText(getUIContext(), msg, Toast.LENGTH_LONG).show();
                });

            } catch (Exception e) {
                log.d("onCreate() 调用失败: " + Log.getStackTraceString(e));
                getMainHandler().post(() -> {
                    Toast.makeText(getUIContext(), "onCreate() 失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * 尝试重置 Application 状态并调用 attach
     */
    private void resetAndTriggerAttach() {
        new Thread(() -> {
            try {
                Application app = (Application) getUIContext().getApplicationContext();
                log.d("尝试重置 Application 状态");

                // 尝试重置 mAttached 标志
                try {
                    java.lang.reflect.Field attachedField = Application.class.getDeclaredField("mAttached");
                    attachedField.setAccessible(true);
                    attachedField.setBoolean(app, false);
                    log.d("已重置 mAttached = false");
                } catch (Exception e) {
                    log.d("重置 mAttached 失败: " + e.getMessage());
                }

                // 尝试重置其他可能的初始化标志
                resetShellInitFlags(app);

                // 现在尝试调用 attach
                triggerAttach();

            } catch (Exception e) {
                log.d("重置状态失败: " + Log.getStackTraceString(e));
                getMainHandler().post(() -> {
                    Toast.makeText(getUIContext(), "重置状态失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * 尝试重置壳的初始化标志
     */
    private void resetShellInitFlags(Application app) {
        // 常见的壳初始化标志字段名
        String[] commonFlagNames = {
            "initialized", "isInitialized", "hasInit", "inited",
            "mInitialized", "mInited", "sInited", "sInitialized",
            "loaded", "isLoaded", "mLoaded", "dexLoaded"
        };

        Class<?> clazz = app.getClass();
        while (clazz != null && clazz != Object.class) {
            for (String flagName : commonFlagNames) {
                try {
                    java.lang.reflect.Field field = clazz.getDeclaredField(flagName);
                    field.setAccessible(true);
                    if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                        field.setBoolean(app, false);
                        log.d("重置 " + clazz.getSimpleName() + "." + flagName + " = false");
                    }
                } catch (Exception ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    /**
     * 搜索并调用壳的初始化方法
     */
    private void searchAndTriggerShellInit() {
        new Thread(() -> {
            try {
                Application app = (Application) getUIContext().getApplicationContext();
                Class<?> appClass = app.getClass();
                log.d("搜索 " + appClass.getName() + " 的初始化方法");

                BaseDexClassLoader beforeLoader = (BaseDexClassLoader) app.getClassLoader();
                int beforeCount = getClassLoaderDexCount(beforeLoader);

                // 搜索可能的初始化方法
                String[] initMethodNames = {
                    "init", "initialize", "onCreate", "attach",
                    "loadDex", "loadLibrary", "prepare", "setup",
                    "doInit", "doInitialize", "realInit"
                };

                java.lang.reflect.Method foundMethod = null;
                for (String methodName : initMethodNames) {
                    try {
                        java.lang.reflect.Method[] methods = appClass.getDeclaredMethods();
                        for (java.lang.reflect.Method method : methods) {
                            if (method.getName().equals(methodName)) {
                                foundMethod = method;
                                log.d("找到方法: " + method.getName() + "(" + java.util.Arrays.toString(method.getParameterTypes()) + ")");
                                break;
                            }
                        }
                        if (foundMethod != null) break;
                    } catch (Exception ignored) {
                    }
                }

                if (foundMethod != null) {
                    foundMethod.setAccessible(true);
                    Class<?>[] paramTypes = foundMethod.getParameterTypes();
                    Object[] args = new Object[paramTypes.length];
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (paramTypes[i] == Context.class) {
                            args[i] = getUIContext();
                        } else if (paramTypes[i] == Application.class) {
                            args[i] = app;
                        } else {
                            args[i] = null;
                        }
                    }
                    foundMethod.invoke(app, args);
                    log.d("调用 " + foundMethod.getName() + " 成功");
                } else {
                    log.d("未找到初始化方法，尝试调用所有无参方法");
                    invokeNoArgMethods(appClass, app);
                }

                int afterCount = getClassLoaderDexCount(beforeLoader);
                log.d("调用后 DEX 数量: " + afterCount);

                final int finalBefore = beforeCount;
                final int finalAfter = afterCount;
                getMainHandler().post(() -> {
                    String msg = "搜索调用完成\n调用前 DEX: " + finalBefore + "\n调用后 DEX: " + finalAfter;
                    if (finalAfter > finalBefore) {
                        msg += "\n检测到新 DEX！建议立即脱壳";
                    }
                    Toast.makeText(getUIContext(), msg, Toast.LENGTH_LONG).show();
                });

            } catch (Exception e) {
                log.d("搜索调用失败: " + Log.getStackTraceString(e));
                getMainHandler().post(() -> {
                    Toast.makeText(getUIContext(), "搜索调用失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * 调用所有无参方法（危险操作）
     */
    private void invokeNoArgMethods(Class<?> clazz, Object obj) {
        java.lang.reflect.Method[] methods = clazz.getDeclaredMethods();
        for (java.lang.reflect.Method method : methods) {
            try {
                if (method.getParameterCount() == 0 && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    method.setAccessible(true);
                    log.d("尝试调用: " + method.getName());
                    method.invoke(obj);
                    log.d("调用成功: " + method.getName());
                }
            } catch (Exception e) {
                log.d("调用 " + method.getName() + " 失败: " + e.getMessage());
            }
        }
    }

    /**
     * 先导出当前 DEX，再尝试触发重新加载
     */
    private void dumpThenTrigger() {
        new Thread(() -> {
            try {
                // 先导出当前 DEX
                log.d("先导出当前 DEX...");
                BaseDexClassLoader loader = (BaseDexClassLoader) getUIContext().getApplicationContext().getClassLoader();
                File dumpPath = new File(dexDumpFile, "before_reload_" + System.currentTimeMillis());
                dumpPath.mkdirs();
                try {
                    dumpByFixedStructure(loader, dumpPath);
                    log.d("已导出到: " + dumpPath.getAbsolutePath());
                } catch (Throwable t) {
                    log.d("导出失败: " + Log.getStackTraceString(t));
                }

                // 然后尝试触发重新加载
                getMainHandler().post(() -> {
                    Toast.makeText(getUIContext(), "已导出当前DEX，现在尝试触发重新加载...", Toast.LENGTH_SHORT).show();
                });

                // 等待一下
                Thread.sleep(500);

                // 尝试重置并调用 attach
                resetAndTriggerAttach();

            } catch (Exception e) {
                log.d("导出并触发失败: " + Log.getStackTraceString(e));
            }
        }).start();
    }

    /**
     * 获取类加载器中的 DEX 数量
     */
    private int getClassLoaderDexCount(BaseDexClassLoader loader) {
        try {
            Object pathList = ReflectUtils.getPathList(loader);
            Object[] dexElements = ReflectUtils.getDexElements(pathList);
            return dexElements != null ? dexElements.length : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 写出Application类加载器的dex
     */
    private void dumpApplicationClassLoader() {
        BaseDexClassLoader targetClassLoader = (BaseDexClassLoader) getUIContext().getApplicationContext().getClassLoader();
        new Thread(() -> dumpOneClassLoader(targetClassLoader)).start();
    }

    private void dumpOneClassLoader(BaseDexClassLoader targetClassLoader) {
        Log.i(DIAG_TAG, "dumpOneClassLoader loader=" + targetClassLoader
                + " class=" + (targetClassLoader == null ? "null" : targetClassLoader.getClass().getName())
                + " dexCount=" + (targetClassLoader == null ? -1 : getClassLoaderDexCount(targetClassLoader)));
        if (targetClassLoader == null || dexDumpFile == null) {
            Log.w(DIAG_TAG, "dumpOneClassLoader skipped: loader/output is null");
            return;
        }
        File classLoaderPath = new File(dexDumpFile, Integer.toHexString(targetClassLoader.hashCode()));
        if (!classLoaderPath.mkdirs()) {
            log.d("classLoader:" + targetClassLoader + "目录创建失败，可能已经存在");
        }
        try {
            dumpClassLoader(targetClassLoader, classLoaderPath);
            getMainHandler().post(() -> Toast.makeText(getUIContext(), targetClassLoader + "dump完成", Toast.LENGTH_SHORT).show());
        } catch (Throwable e) {
            log.d("类加载器:" + targetClassLoader + "dump失败" + Log.getStackTraceString(e));
        }
    }

    /**
     * 显示当前Application信息
     */
    private void showCurrentApplication() {
        String name = getUIContext().getApplicationContext().getClass().getName();
        AlertDialog.Builder applicationBuilder = new AlertDialog.Builder(getUIContext());
        applicationBuilder.setTitle("当前application");
        applicationBuilder.setMessage(name);
        applicationBuilder.setNegativeButton("取消", null);
        applicationBuilder.setPositiveButton("复制到剪切板", (dialog1, which1) -> {
            ClipboardManager clipboardManager = (ClipboardManager) getUIContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clipData = ClipData.newPlainText("ApplicationName", name);
            clipboardManager.setPrimaryClip(clipData);
            Toast.makeText(getUIContext(), "复制到剪切板", Toast.LENGTH_SHORT).show();
        });
        applicationBuilder.create().show();
    }

    /**
     * 写出选定的类加载器dex
     */
    private void dumpSelectedClassLoader() {
        AlertDialog.Builder selectClassLoaderDialog = new AlertDialog.Builder(getUIContext());
        selectClassLoaderDialog.setTitle("选择类加载器");
        CharSequence[] classLoaderList = new CharSequence[allClassLoader.size()];
        for (int i = 0; i < allClassLoader.size(); i++) {
            BaseDexClassLoader classLoader = allClassLoader.get(i);
            classLoaderList[i] = classLoader.toString();
        }
        boolean[] checkList = new boolean[allClassLoader.size()];
        selectClassLoaderDialog.setMultiChoiceItems(classLoaderList, checkList, (dialog1, which1, isChecked) -> checkList[which1] = isChecked);

        selectClassLoaderDialog.setNegativeButton("取消", null);
        selectClassLoaderDialog.setPositiveButton("写出选定", ((dialog1, which1) -> new Thread(() -> {
            for (int i = 0; i < checkList.length; i++) {
                if (checkList[i]) {
                    dumpOneClassLoader(allClassLoader.get(i));
                }
            }
        }).start()));
        selectClassLoaderDialog.create().show();
    }

    /**
     * 写出所有类加载器dex
     */
    private void dumpAllClassLoaders() {
        new Thread(() -> {
            List<BaseDexClassLoader> loaders;
            synchronized (allClassLoader) {
                loaders = new ArrayList<>(allClassLoader);
            }
            for (BaseDexClassLoader targetClassLoader : loaders) {
                dumpOneClassLoader(targetClassLoader);
            }
        }).start();
    }

    /**
     * 创建黑名单文件
     */
    private void createBlacklistFile() {
        try {
            if (!blackListFile.createNewFile()) {
                log.d("创建黑名单文件失败，文件可能已存在");
            }
            AlertDialog.Builder blackListDialog = new AlertDialog.Builder(getUIContext());
            blackListDialog.setTitle("启用正则");
            blackListDialog.setMessage("是否启用正则黑名单？");
            blackListDialog.setNegativeButton("不启用", ((dialog1, which1) -> Toast.makeText(getUIContext(), "创建文件成功:" + blackListFile.getAbsolutePath(), Toast.LENGTH_SHORT).show()));
            blackListDialog.setPositiveButton("启用", ((dialog1, which1) -> {
                BufferedWriter writer;
                try {
                    writer = new BufferedWriter(new FileWriter(blackListFile));
                    writer.write("enable pattern\n");
                    writer.flush();
                    writer.close();
                    Toast.makeText(getUIContext(), "创建文件成功:" + blackListFile.getAbsolutePath() + " 正则规则写入成功", Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    Toast.makeText(getUIContext(), "创建文件成功:" + blackListFile.getAbsolutePath() + " 正则规则写入失败", Toast.LENGTH_SHORT).show();
                }
            }));
            blackListDialog.create().show();
        } catch (IOException e) {
            Toast.makeText(getUIContext(), "创建黑名单文件失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 显示热修复配置对话框
     */
    private void showHotFixConfig() {
        AlertDialog.Builder hotFixDialog = new AlertDialog.Builder(getUIContext());
        hotFixDialog.setTitle("热修复配置");
        CharSequence[] config = new CharSequence[]{"dex热修复", "so热修复", "Application类加载器", "所有类加载器"};
        boolean[] checkedList = new boolean[config.length];
        checkedList[0] = configSharedPreferences.getBoolean("DexInject", true);
        checkedList[1] = configSharedPreferences.getBoolean("SoInject", false);
        checkedList[2] = configSharedPreferences.getBoolean("InjectApplicationClassLoader", true);
        checkedList[3] = configSharedPreferences.getBoolean("InjectAllClassLoader", false);
        hotFixDialog.setMultiChoiceItems(config, checkedList, ((dialog1, which1, isChecked) -> {
            switch (which1) {
                // dex热修复
                case 0:
                    if (!isChecked) {
                        AlertDialog.Builder tipDialog = new AlertDialog.Builder(getUIContext());
                        tipDialog.setTitle("提示");
                        tipDialog.setMessage("确定启用热修复但是不启用dex热修复吗？");
                        tipDialog.setPositiveButton("我确定", ((dialog2, which2) -> {
                            checkedList[which1] = false;
                            ListView listView = ((AlertDialog) dialog1).getListView();
                            if (listView != null) {
                                ((BaseAdapter) listView.getAdapter()).notifyDataSetChanged();
                            }
                        }));
                        tipDialog.setNegativeButton("返回", ((dialog2, which2) -> {
                            checkedList[which1] = true;
                            ListView listView = ((AlertDialog) dialog1).getListView();
                            if (listView != null) {
                                ((BaseAdapter) listView.getAdapter()).notifyDataSetChanged();
                            }
                        }));
                        tipDialog.setCancelable(false);
                        tipDialog.create().show();
                    }
                    break;
                // so热修复
                case 1:
                    if (isChecked) {
                        AlertDialog.Builder tipDialog = new AlertDialog.Builder(getUIContext());
                        tipDialog.setTitle("提示");
                        tipDialog.setMessage("so热修复并不一定生效，可能存在一定的bug，确定启用so热修复吗？");
                        tipDialog.setPositiveButton("我确定", ((dialog2, which2) -> {
                            checkedList[1] = true;
                            ListView listView = ((AlertDialog) dialog1).getListView();
                            listView.setItemChecked(1, true);
                        }));
                        tipDialog.setNegativeButton("返回", ((dialog2, which2) -> {
                            checkedList[1] = false;
                            ListView listView = ((AlertDialog) dialog1).getListView();
                            listView.setItemChecked(1, false);
                        }));
                        tipDialog.setCancelable(false);
                        tipDialog.create().show();
                    }
                    break;
                // Application类加载器
                case 2:
                    if (isChecked) {
                        checkedList[3] = false;
                        ListView listView = ((AlertDialog) dialog1).getListView();
                        listView.setItemChecked(3, false);
                    } else {
                        checkedList[3] = true;
                        ListView listView = ((AlertDialog) dialog1).getListView();
                        listView.setItemChecked(3, true);
                    }
                    break;
                // 所有类加载器
                case 3:
                    if (isChecked) {
                        checkedList[2] = false;
                        ListView listView = ((AlertDialog) dialog1).getListView();
                        listView.setItemChecked(2, false);
                    } else {
                        checkedList[2] = true;
                        ListView listView = ((AlertDialog) dialog1).getListView();
                        listView.setItemChecked(2, true);
                    }
                    break;
            }
        }));
        hotFixDialog.setNegativeButton("取消", null);
        hotFixDialog.setPositiveButton("执行", ((dialog1, which1) -> {
            SharedPreferences.Editor editor = configSharedPreferences.edit();
            editor.putBoolean("DexInject", checkedList[0]);
            editor.putBoolean("SoInject", checkedList[1]);
            editor.putBoolean("InjectApplicationClassLoader", checkedList[2]);
            editor.putBoolean("InjectAllClassLoader", checkedList[3]);
            editor.apply();
            Toast.makeText(getUIContext(), "设置成功", Toast.LENGTH_SHORT).show();
            File dataFile = Objects.requireNonNull(getUIContext().getExternalCacheDir()).getParentFile();
            injectPathFile = new File(dataFile, "dump");
            if (!injectPathFile.mkdirs()) {
                log.d("dump目录创建失败，可能已存在");
            }
            ClipboardManager clipboardManager = (ClipboardManager) getUIContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clipData = ClipData.newPlainText("InjectPath", injectPathFile.getAbsolutePath());
            clipboardManager.setPrimaryClip(clipData);
            Toast.makeText(getUIContext(), "热修复路径已复制到剪切板", Toast.LENGTH_SHORT).show();
        }));
        hotFixDialog.create().show();
    }

    /**
     * 显示加载so对话框
     */
    private void showLoadSoDialog() {
        AlertDialog.Builder tipDialog = new AlertDialog.Builder(getUIContext());
        tipDialog.setTitle("提示");
        tipDialog.setMessage("主动加载so需要先开启so热修复功能。若选择so文件弹窗无内容，先开启so热修复后将so文件移动到so热修复目录后再使用本功能。启用本功能后宿主程序会在下次启动时尝试加载选定的so文件。so文件需统一为libxxx.so文件格式");
        tipDialog.setNegativeButton("返回", null);
        tipDialog.setPositiveButton("选择so文件", ((dialog1, which1) -> {
            AlertDialog.Builder selectSoDialog = new AlertDialog.Builder(getUIContext());
            selectSoDialog.setTitle("选择要加载的so文件");
            File soFixPath = (soInjectPathFile == null ? new File(injectPathFile, "so") : soInjectPathFile);
            File[] soFiles = soFixPath.listFiles();
            if (soFiles == null || soFiles.length == 0) {
                selectSoDialog.setMessage("未搜索到so文件");
                selectSoDialog.setPositiveButton("知道了", null);
            } else {
                String[] soName = new String[soFiles.length];
                boolean[] soCheckList = new boolean[soName.length];
                Set<String> soLoadList = configSharedPreferences.getStringSet("soNeededLoad", new HashSet<>());
                ArrayList<String> loadSoName = new ArrayList<>();
                for (int i = 0; i < soFiles.length; i++) {
                    File soFile = soFiles[i];
                    soName[i] = soFile.getName();
                    if (soLoadList.contains(soName[i])) {
                        soCheckList[i] = true;
                    }
                }

                selectSoDialog.setMultiChoiceItems(soName, soCheckList, ((dialog2, which2, isChecked) -> {
                    soCheckList[which2] = isChecked;
                    if (isChecked) {
                        loadSoName.add(soName[which2]);
                    } else {
                        loadSoName.remove(soName[which2]);
                    }
                }));
                selectSoDialog.setPositiveButton("确认", ((dialog2, which2) -> {
                    SharedPreferences.Editor editor = configSharedPreferences.edit();
                    editor.putStringSet("soNeededLoad", new HashSet<>(loadSoName));
                    editor.apply();
                }));
                selectSoDialog.setNegativeButton("取消", null);
                selectSoDialog.setCancelable(false);
            }
            selectSoDialog.create().show();
        }));
        tipDialog.create().show();
    }

    /**
     * 取消悬浮窗显示
     */
    private void cancelFloatWindow() {
        try {
            SharedPreferences.Editor editor = configSharedPreferences.edit();
            editor.putBoolean("showFloat", false);
            editor.apply();
            Activity topActivity = null;
            ActivityThread currentActivityThread = ActivityThread.currentActivityThread();
            ArrayMap<?, ?> activities = ReflectUtils.getMActivities(currentActivityThread);
            assert activities != null;
            for (Object activityRecord : activities.values()) {
                topActivity = ReflectUtils.getActivity(activityRecord);
            }
            if (topActivity != null) {
                viewGroupList.clear();
                topActivity.recreate();
            }
        } catch (Exception e) {
            log.d("获取顶层Activity失败");
        }
    }

    /**
     * 使用DexKit导出dex
     */
    private void exportWithDexKit() {
        DexKitBridge bridge = DexKitBridge.create(getUIContext().getClassLoader(), true);
        File dexkitFile = new File(getUIContext().getExternalCacheDir(), "dexkitDump");
        try {
            ToolClass.createDirectory(dexkitFile);
        } catch (IOException e) {
            log.d("dexkit文件创建失败");
        }
        bridge.exportDexFile(dexkitFile.getAbsolutePath());
        ClipboardManager clipboardManager = (ClipboardManager) getUIContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clipData = ClipData.newPlainText("dexkitPath", dexkitFile.getAbsolutePath());
        clipboardManager.setPrimaryClip(clipData);
        Toast.makeText(getUIContext(), "dump目录已复制到剪切板", Toast.LENGTH_SHORT).show();
        bridge.close();
    }

    /**
     * 执行内存扫描dump
     */
    private void performMemoryScanDump() {
        new Thread(() -> {
            try {
                log.d("开始内存特征匹配模式");
                File outputDir = new File(dexDumpFile, "memory_scan_" + System.currentTimeMillis());
                if (!outputDir.mkdirs()) {
                    log.d("创建输出目录失败");
                }
                int count = dumpDexByMemoryScan(outputDir.getAbsolutePath());
                final int finalCount = count;
                getMainHandler().post(() -> {
                    Toast.makeText(getUIContext(), "内存扫描完成，找到 " + finalCount + " 个DEX文件\n保存到: " + outputDir.getAbsolutePath(), Toast.LENGTH_LONG).show();
                });
                log.d("内存扫描完成，找到 " + count + " 个DEX文件");
            } catch (Exception e) {
                log.d("内存扫描失败: " + Log.getStackTraceString(e));
                getMainHandler().post(() -> {
                    Toast.makeText(getUIContext(), "内存扫描失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /**
     * 执行Root内存dump
     * 通过Root权限直接读取目标进程内存，不依赖Xposed注入
     */
    private void performRootMemoryDump() {
        new Thread(() -> {
            try {
                Context context = getUIContext();
                if (context == null) {
                    log.d("UIContext is null");
                    return;
                }

                log.d("开始Root内存脱壳模式");
                String packageName = context.getPackageName();

                // 检查Root权限
                if (!RootMemoryScanner.checkRootAvailable()) {
                    getMainHandler().post(() -> {
                        Toast.makeText(getUIContext(), "Root权限不可用，请检查Root状态", Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                File outputDir = new File(dexDumpFile, "root_memory_" + System.currentTimeMillis());
                if (!outputDir.mkdirs()) {
                    log.d("创建输出目录失败");
                }

                RootMemoryScanner scanner = new RootMemoryScanner(context);
                int count = scanner.dumpDexFromProcess(packageName, outputDir);
                final int finalCount = count;

                getMainHandler().post(() -> {
                    String msg;
                    if (finalCount >= 0) {
                        msg = "Root内存脱壳完成，找到 " + finalCount + " 个DEX文件\n保存到: " + outputDir.getAbsolutePath();
                    } else {
                        msg = "Root内存脱壳失败 (错误码: " + finalCount + ")\n请确认目标进程正在运行且Root权限正常";
                    }
                    Toast.makeText(getUIContext(), msg, Toast.LENGTH_LONG).show();
                });
                log.d("Root内存脱壳完成，找到 " + count + " 个DEX文件");
            } catch (Exception e) {
                log.d("Root内存脱壳失败: " + Log.getStackTraceString(e));
                getMainHandler().post(() -> {
                    Toast.makeText(getUIContext(), "Root内存脱壳失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /**
     * 安装 LoadClass Hook（内部方法，在 Application.attach 时调用）
     */
    private void installLoadClassHookInternal(Context context) {
        try {
            log.d("========== 安装 LoadClass Hook (Dobby) ==========");

            File baseDir = context.getExternalCacheDir();
            if (baseDir == null) {
                log.d("无法获取外部缓存目录");
                return;
            }
            File outputDir = new File(baseDir.getParentFile(), "dump/loadclass_hook");
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                log.d("创建输出目录失败，可能已存在: " + outputDir.getAbsolutePath());
            }
            setHookOutputDir(outputDir.getAbsolutePath());
            log.d("输出目录: " + outputDir.getAbsolutePath());

            // 安装 LoadClass Hook
            if (installLoadClassHook()) {
                log.d("LoadClass Hook 安装成功");
                log.d("API Level: " + getApiLevel());
                log.d(deepUnpack ? "深度脱壳已启用：将在固定结构 dump 时解析 code-item"
                        : "深度脱壳未启用：跳过 code-item 解析");
            } else {
                log.d("LoadClass Hook 安装失败");
                String symbols = listLoadClassSymbols();
                log.d("符号列表:\n" + symbols);
            }
        } catch (Exception e) {
            log.d("安装 LoadClass Hook 失败: " + Log.getStackTraceString(e));
        }
    }

    /**
     * 显示 LoadClass Hook 状态对话框
     */
    private void showLoadClassHookStatus() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(getUIContext());
        dialog.setTitle("LoadClass Hook 状态");

        StringBuilder info = new StringBuilder();
        info.append("API Level: ").append(getApiLevel()).append("\n");
        info.append("Hook 状态: ").append(isLoadClassHookActive() ? "已激活" : "未激活").append("\n");
        info.append("已 dump DEX 数量: ").append(getDumpedDexCount()).append("\n");

        File baseDir = getUIContext().getExternalCacheDir();
        if (baseDir != null) {
            info.append("\n输出目录: ").append(new File(baseDir.getParentFile(), "dump/loadclass_hook").getAbsolutePath());
        }

        dialog.setMessage(info.toString());
        dialog.setPositiveButton("刷新", (d, which) -> showLoadClassHookStatus());
        dialog.setNegativeButton("重置计数", (d, which) -> {
            resetDumpCount();
            Toast.makeText(getUIContext(), "计数已重置", Toast.LENGTH_SHORT).show();
        });
        dialog.setNeutralButton("列出符号", (d, which) -> {
            String symbols = listLoadClassSymbols();
            log.d("符号列表:\n" + symbols);
            new AlertDialog.Builder(getUIContext())
                    .setTitle("可用符号")
                    .setMessage(symbols)
                    .setPositiveButton("确定", null)
                    .show();
        });
        dialog.create().show();
    }

    private void fixCodeItemInsns() {
        Context context = getUIContext();
        if (context == null) return;
        String[] options = {"严格还原（长度必须匹配）", "强制还原（忽略长度不匹配，截断/补零）"};
        new AlertDialog.Builder(context)
                .setTitle("还原 code_item(insns)")
                .setItems(options, (dialog, which) -> {
                    boolean force = which == 1;
                    doFixCodeItemInsns(force);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doFixCodeItemInsns(boolean forceMismatch) {
        new Thread(() -> {
            try {
                Context context = getUIContext();
                if (context == null) {
                    return;
                }
                File baseDir = context.getExternalCacheDir();
                if (baseDir == null) {
                    return;
                }
                File outputDir = unshellMode == MODE_LOADCLASS_HOOK ? new File(baseDir.getParentFile(), "dump/loadclass_hook") : new File(baseDir.getParentFile(), "dump");
                DexCodeFixer.Result result = unshellMode == MODE_LOADCLASS_HOOK
                        ? DexCodeFixer.fixDirectory(outputDir, forceMismatch)
                        : DexCodeFixer.fixRecursively(outputDir, forceMismatch);
                getMainHandler().post(() -> Toast.makeText(getUIContext(), "还原完成: DEX " + result.dexFiles + ", 修复 " + result.fixedFiles + ", 应用 " + result.applied + ", 跳过 " + result.skipped + (forceMismatch ? " (强制)" : " (严格)") + "\n输出根目录: " + outputDir.getAbsolutePath(), Toast.LENGTH_LONG).show());
                log.d("code_item还原完成: dex=" + result.dexFiles + ", fixed=" + result.fixedFiles + ", applied=" + result.applied + ", skipped=" + result.skipped + ", mismatch=" + result.lengthMismatch + ", force=" + forceMismatch);
            } catch (Exception e) {
                log.d("code_item还原失败: " + Log.getStackTraceString(e));
                getMainHandler().post(() -> Toast.makeText(getUIContext(), "还原失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /**
     * 显示反射工具菜单
     */
    private void showReflectionMenu() {
        try {
            // 获取所有 ClassLoader 的列表
            List<ClassLoader> classLoaders;
            synchronized (allClassLoader) {
                classLoaders = new ArrayList<>(allClassLoader);
            }
            ClassLoader primaryLoader = classLoaders.isEmpty() ? getUIContext().getClassLoader() : classLoaders.get(0);
            List<ClassLoader> additionalLoaders = classLoaders.size() > 1 ? classLoaders.subList(1, classLoaders.size()) : new ArrayList<>();

            com.zitan.cdumpdex.reflection.ui.ReflectionLayout.showReflectionDialog(getUIContext(), primaryLoader, additionalLoaders);
        } catch (Exception e) {
            log.d("显示反射菜单失败: " + Log.getStackTraceString(e));
            Toast.makeText(getUIContext(), "显示反射菜单失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 显示反编译指定类的对话框
     */
    @SuppressLint("InflateParams")
    private void showDecompileClassDialog() {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getUIContext());
        dialogBuilder.setTitle("反编译指定类");

        // 创建主布局
        LinearLayout mainLayout = new LinearLayout(getUIContext());
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(40, 20, 40, 20);

        // 输入框
        EditText classInput = new EditText(getUIContext());
        classInput.setHint("输入类名（如：com.example.MyClass）");
        mainLayout.addView(classInput);

        // 类名列表
        ListView classListView = new ListView(getUIContext());
        classListView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 400));
        List<String> allClassNames = Collections.synchronizedList(new ArrayList<>());
        ArrayList<String> filteredClassNames = new ArrayList<>();
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                getUIContext(), android.R.layout.simple_list_item_1, filteredClassNames);
        classListView.setAdapter(adapter);
        mainLayout.addView(classListView);

        // 状态提示
        TextView statusText = new TextView(getUIContext());
        statusText.setText("正在加载类列表...");
        statusText.setPadding(0, 10, 0, 10);
        mainLayout.addView(statusText);

        // 加载进度
        TextView progressText = new TextView(getUIContext());
        progressText.setVisibility(android.view.View.GONE);
        mainLayout.addView(progressText);

        dialogBuilder.setView(mainLayout);
        dialogBuilder.setNegativeButton("取消", null);
        dialogBuilder.setPositiveButton("反编译", (dialog, which) -> {
            String className = classInput.getText().toString().trim();
            if (!className.isEmpty()) {
                decompileClass(className);
            } else {
                Toast.makeText(getUIContext(), "请输入类名", Toast.LENGTH_SHORT).show();
            }
        });

        AlertDialog dialog = dialogBuilder.create();
        dialog.show();

        // 异步加载所有类名
        new Thread(() -> {
            try {
                loadAllClassNames(allClassNames);
                getMainHandler().post(() -> {
                    statusText.setText("已加载 " + allClassNames.size() + " 个类");
                    // 加载完成后重新触发过滤，避免用户已输入内容但列表为空
                    String query = classInput.getText().toString().trim().toLowerCase();
                    filteredClassNames.clear();
                    if (!query.isEmpty()) {
                        synchronized (allClassNames) {
                            int count2 = 0;
                            for (String cn : allClassNames) {
                                if (cn.toLowerCase().contains(query)) {
                                    filteredClassNames.add(cn);
                                    count2++;
                                    if (count2 >= 100) break;
                                }
                            }
                        }
                    }
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                log.d("加载类列表失败: " + e.getMessage());
                getMainHandler().post(() -> {
                    statusText.setText("加载类列表失败");
                });
            }
        }).start();

        // 输入监听
        classInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                String query = s.toString().trim().toLowerCase();
                filteredClassNames.clear();
                if (query.isEmpty()) {
                    adapter.notifyDataSetChanged();
                    return;
                }
                int count2 = 0;
                synchronized (allClassNames) {
                    for (String className : allClassNames) {
                        if (className.toLowerCase().contains(query)) {
                            filteredClassNames.add(className);
                            count2++;
                            if (count2 >= 100) break; // 限制结果数量
                        }
                    }
                }
                adapter.notifyDataSetChanged();
            }
        });

        // 列表点击
        classListView.setOnItemClickListener((parent, view, position, id) -> {
            String selectedClass = filteredClassNames.get(position);
            classInput.setText(selectedClass);
            classInput.setSelection(selectedClass.length());
        });
    }

    /**
     * 加载所有类名
     */
    private void loadAllClassNames(List<String> classNames) throws Exception {
        synchronized (allClassLoader) {
            for (BaseDexClassLoader classLoader : allClassLoader) {
                try {
                    Object pathList = ReflectUtils.getPathList(classLoader);
                    Object[] dexElements = ReflectUtils.getDexElements(pathList);
                    for (Object dexElement : dexElements) {
                        DexFile dexFile = ReflectUtils.getDexFile(dexElement);
                        if (dexFile != null) {
                            Object mCookie = ReflectUtils.getMCookie(dexFile);
                            if (mCookie != null) {
                                String[] nameList = ReflectUtils.getNameList(mCookie);
                                if (nameList != null) {
                                    for (String name : nameList) {
                                        if (!classNames.contains(name)) {
                                            classNames.add(name);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.d("加载ClassLoader类名失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 反编译指定类
     */
    private void decompileClass(String className) {
        new Thread(() -> {
            try {
                // 查找类所在的DexFile
                DexFileInfo dexInfo = findClassInDexFiles(className);
                if (dexInfo == null) {
                    getMainHandler().post(() -> {
                        Toast.makeText(getUIContext(), "未找到类: " + className, Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 反编译smali
                String smaliCode = decompileToSmali(dexInfo, className);
                if (smaliCode == null) {
                    getMainHandler().post(() -> {
                        Toast.makeText(getUIContext(), "Smali反编译失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 显示编辑对话框
                String finalSmaliCode = smaliCode;
                DexFileInfo finalDexInfo = dexInfo;
                getMainHandler().post(() -> {
                    showSmaliEditorDialog(className, finalSmaliCode, finalDexInfo);
                });

            } catch (Exception e) {
                log.d("反编译失败: " + e.getMessage());
                getMainHandler().post(() -> {
                    Toast.makeText(getUIContext(), "反编译失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /**
     * DEX文件信息
     */
    private static class DexFileInfo {
        DexFile dexFile;
        Object dexElement;
        Object mCookie;
        int cookieIndex;
        byte[] dexBytes;

        DexFileInfo(DexFile dexFile, Object dexElement, Object mCookie, int cookieIndex) {
            this.dexFile = dexFile;
            this.dexElement = dexElement;
            this.mCookie = mCookie;
            this.cookieIndex = cookieIndex;
        }
    }

    /**
     * 在所有DexFile中查找指定类
     */
    private DexFileInfo findClassInDexFiles(String className) throws Exception {
        synchronized (allClassLoader) {
            for (BaseDexClassLoader classLoader : allClassLoader) {
                try {
                    Object pathList = ReflectUtils.getPathList(classLoader);
                    Object[] dexElements = ReflectUtils.getDexElements(pathList);
                    for (Object dexElement : dexElements) {
                        DexFile dexFile = ReflectUtils.getDexFile(dexElement);
                        if (dexFile != null) {
                            Object mCookie = ReflectUtils.getMCookie(dexFile);
                            if (mCookie instanceof long[]) {
                                long[] cookies = (long[]) mCookie;

                                // 先检查类名是否在这个 DexFile 中
                                String[] nameList = ReflectUtils.getNameList(mCookie);
                                boolean found = false;
                                if (nameList != null) {
                                    for (String name : nameList) {
                                        if (name.equals(className)) {
                                            found = true;
                                            break;
                                        }
                                    }
                                }

                                if (!found) {
                                    continue;  // 类不在这个 DexFile 中，跳过
                                }

                                // 类在这个 DexFile 中，找到对应的 cookie 索引
                                // mCookie[0] 是 oat 文件，从索引 1 开始是 dex 文件
                                for (int i = 1; i < cookies.length; i++) {
                                    long cookie = cookies[i];
                                    if (cookie == 0) {
                                        continue;
                                    }

                                    // 尝试从这个 cookie 读取 dex 并查找类
                                    File tempDex = new File(dexDumpFile, "temp_find_" + System.currentTimeMillis() + "_" + i + ".dex");
                                    try {
                                        if (writeDexToFile(cookie, tempDex.getAbsolutePath())) {
                                            // 使用 SmaliUtils 检查类是否存在
                                            org.jf.dexlib2.dexbacked.DexBackedDexFile dexBackedFile = SmaliUtils.fromDexFile(tempDex.getAbsolutePath());
                                            if (SmaliUtils.containsClass(dexBackedFile, className)) {
                                                log.d("找到类 " + className + " 在 cookie 索引 " + i);
                                                return new DexFileInfo(dexFile, dexElement, mCookie, i);
                                            }
                                        }
                                    } catch (Exception e) {
                                        // 忽略解析错误，继续尝试下一个
                                    } finally {
                                        tempDex.delete();
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.d("查找类失败: " + e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * 反编译为Smali代码
     */
    private String decompileToSmali(DexFileInfo dexInfo, String className) throws Exception {
        // 从cookie读取DEX数据
        if (dexInfo.mCookie instanceof long[]) {
            long[] cookies = (long[]) dexInfo.mCookie;
            if (dexInfo.cookieIndex < cookies.length) {
                long cookie = cookies[dexInfo.cookieIndex];
                // 使用native方法dump dex到临时文件
                File tempDex = new File(dexDumpFile, "temp_" + System.currentTimeMillis() + ".dex");
                try {
                    if (writeDexToFile(cookie, tempDex.getAbsolutePath())) {
                        // 使用SmaliUtils反编译
                        org.jf.dexlib2.dexbacked.DexBackedDexFile dexFile = SmaliUtils.fromDexFile(tempDex.getAbsolutePath());
                        return SmaliUtils.getSmaliFromClassDef(dexFile, className);
                    }
                } finally {
                    tempDex.delete();
                }
            }
        }
        return null;
    }

    /**
     * 显示Smali编辑对话框
     */
    private void showSmaliEditorDialog(String className, String smaliCode, DexFileInfo dexInfo) {
        SmaliEditorDialog editorDialog = new SmaliEditorDialog(getUIContext());
        editorDialog.setClassName(className);
        editorDialog.setSmaliCode(smaliCode);
        editorDialog.setDexInjectPath(dexInjectPathFile);

        // 热修复监听
        editorDialog.setOnHotFixListener((clsName, code) -> {
            performHotFix(clsName, code);
        });

        // 转Java监听
        editorDialog.setOnDecompileJavaListener(clsName -> {
            decompileToJava(clsName, dexInfo);
        });

        editorDialog.show();
    }

    /**
     * 执行热修复
     */
    private void performHotFix(String className, String smaliCode) {
        new Thread(() -> {
            try {
                // 生成dex文件名
                String dexName = className.replace('.', '_') + "_" + System.currentTimeMillis() + ".dex";
                File outputDex = new File(dexInjectPathFile, dexName);

                // 编译smali为dex
                boolean success = SmaliCompiler.compileToFile(smaliCode, className, outputDex);
                if (success) {
                    getMainHandler().post(() -> {
                        Toast.makeText(getUIContext(), "热修复dex已生成: " + outputDex.getName() + "\n重启应用后生效", Toast.LENGTH_LONG).show();
                    });
                    log.d("热修复dex生成成功: " + outputDex.getAbsolutePath());
                } else {
                    getMainHandler().post(() -> {
                        Toast.makeText(getUIContext(), "Smali编译失败，请检查语法", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                log.d("热修复失败: " + e.getMessage());
                getMainHandler().post(() -> {
                    Toast.makeText(getUIContext(), "热修复失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /**
     * 反编译为Java代码
     */
    private void decompileToJava(String className, DexFileInfo dexInfo) {
        new Thread(() -> {
            try {
                if (!(dexInfo.mCookie instanceof long[])) {
                    getMainHandler().post(() -> {
                        Toast.makeText(getUIContext(), "Java反编译失败: 无效的Cookie类型", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                long[] cookies = (long[]) dexInfo.mCookie;
                if (dexInfo.cookieIndex >= cookies.length) {
                    getMainHandler().post(() -> {
                        Toast.makeText(getUIContext(), "Java反编译失败: Cookie索引越界", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                long cookie = cookies[dexInfo.cookieIndex];
                File tempDex = new File(dexDumpFile, "temp_java_" + System.currentTimeMillis() + ".dex");
                try {
                    if (!writeDexToFile(cookie, tempDex.getAbsolutePath())) {
                        getMainHandler().post(() -> {
                            Toast.makeText(getUIContext(), "Java反编译失败: 写出DEX失败", Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }
                    String javaCode = JavaDecompileUtils.decompileClassFromDex(tempDex.getAbsolutePath(), className);
                    if (javaCode != null) {
                        getMainHandler().post(() -> {
                            showJavaCodeDialog(className, javaCode);
                        });
                    } else {
                        getMainHandler().post(() -> {
                            Toast.makeText(getUIContext(), "Java反编译失败，可能类不存在", Toast.LENGTH_SHORT).show();
                        });
                    }
                } finally {
                    tempDex.delete();
                }
            } catch (Exception e) {
                log.d("Java反编译失败: " + e.getMessage());
                getMainHandler().post(() -> {
                    Toast.makeText(getUIContext(), "Java反编译失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /**
     * 显示Java代码对话框
     */
    private void showJavaCodeDialog(String className, String javaCode) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getUIContext());
        dialogBuilder.setTitle("Java: " + className);

        ScrollView scrollView = new ScrollView(getUIContext());
        EditText codeView = new EditText(getUIContext());
        codeView.setText(javaCode);
        codeView.setTypeface(android.graphics.Typeface.MONOSPACE);
        codeView.setTextSize(12);
        codeView.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        codeView.setPadding(20, 20, 20, 20);
        codeView.setEnabled(false); // 只读
        scrollView.addView(codeView);

        dialogBuilder.setView(scrollView);
        dialogBuilder.setPositiveButton("复制", (dialog, which) -> {
            ClipboardManager clipboard = (ClipboardManager) getUIContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("java_code", javaCode);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(getUIContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });
        dialogBuilder.setNegativeButton("关闭", null);

        AlertDialog dialog = dialogBuilder.create();
        dialog.show();
        dialog.getWindow().setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
    }
}
