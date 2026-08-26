package com.zitan.cdumpdex.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 配置管理类
 * 管理脱壳模块的配置信息
 */
public class ConfigManager {

    private static final String CONFIG_FILE_NAME = "cdumpdex_config.json";

    private final Context context;
    private final SharedPreferences sharedPreferences;
    private final Gson gson;

    public ConfigManager(Context context) {
        this.context = context;
        this.sharedPreferences = context.getSharedPreferences("cDumpDex_config", Context.MODE_PRIVATE);
        this.gson = new Gson();
    }

    /**
     * 配置结构
     */
    public static class Config {
        public String unshellMode = "fixed"; // fixed, memory_scan, loadclass_hook
        public boolean enableHotFix = false;
        public String permissionMethod = "none"; // shizuku, root, none
        public boolean deepUnpack = false; // 深度脱壳：固定结构 dump 时解析 code-item
        public boolean methodTriggerEnabled = false; // 方法级触发，默认关闭
        public int methodTriggerMaxMethods = 200;
        public int methodTriggerMaxClasses = 100;
        public int methodTriggerTimeoutMs = 300;
        public int methodTriggerTotalTimeoutMs = 10000;
        public int methodTriggerMaxFailures = 20;

        public Map<String, Object> customConfig = new HashMap<>();
    }

    /**
     * 获取当前配置
     */
    public Config getConfig() {
        String json = sharedPreferences.getString("config", null);
        if (json == null) {
            return new Config();
        }

        try {
            return gson.fromJson(json, Config.class);
        } catch (Exception e) {
            return new Config();
        }
    }

    /**
     * 保存配置
     */
    public void saveConfig(Config config) {
        String json = gson.toJson(config);
        sharedPreferences.edit().putString("config", json).apply();
    }

    /**
     * 设置脱壳模式
     */
    public void setUnshellMode(String mode) {
        Config config = getConfig();
        config.unshellMode = mode;
        saveConfig(config);
    }

    /**
     * 获取脱壳模式
     */
    public String getUnshellMode() {
        return getConfig().unshellMode;
    }

    /**
     * 设置深度脱壳
     */
    public void setDeepUnpack(boolean enabled) {
        Config config = getConfig();
        config.deepUnpack = enabled;
        saveConfig(config);
    }

    /**
     * 获取深度脱壳
     */
    public boolean getDeepUnpack() {
        return getConfig().deepUnpack;
    }

    public void setMethodTriggerEnabled(boolean enabled) {
        Config config = getConfig();
        config.methodTriggerEnabled = enabled;
        saveConfig(config);
    }

    public boolean getMethodTriggerEnabled() {
        return getConfig().methodTriggerEnabled;
    }

    /**
     * 设置权限获取方式
     */
    public void setPermissionMethod(String method) {
        Config config = getConfig();
        config.permissionMethod = method;
        saveConfig(config);
    }

    /**
     * 获取权限获取方式
     */
    public String getPermissionMethod() {
        return getConfig().permissionMethod;
    }

    /**
     * 将配置转换为JSON字符串
     */
    public String configToJson() {
        return gson.toJson(getConfig());
    }

    /**
     * 从JSON字符串解析配置
     */
    public Config configFromJson(String json) {
        try {
            return gson.fromJson(json, Config.class);
        } catch (Exception e) {
            return new Config();
        }
    }

    /**
     * 写入配置到目标应用
     * 根据当前设置的权限方式选择写入方法
     */
    public boolean writeConfigToTargetApp(String targetPackage) {
        String json = configToJson();
        String permissionMethod = getPermissionMethod();

        switch (permissionMethod) {
            case "shizuku":
                ShizukuHelper shizukuHelper = new ShizukuHelper(context);
                return shizukuHelper.writeJsonConfig(targetPackage, json);

            case "root":
                RootHelper rootHelper = new RootHelper();
                boolean result = rootHelper.writeJsonConfig(targetPackage, json);
                rootHelper.closeShell();
                return result;

            default:
                return false;
        }
    }

    /**
     * 检查目标应用是否已有配置文件
     */
    public boolean hasConfigInTargetApp(String targetPackage) {
        String configPath = "/storage/emulated/0/Android/data/" + targetPackage + "/files/" + CONFIG_FILE_NAME;
        return new java.io.File(configPath).exists();
    }

    /* =================== 主动调用模式（多选） =================== */

    /** Java 解析类名并通过目标 ClassLoader 主动加载。 */
    public static final String ACTIVE_CALL_MODE_XPOSED_JAVA = "xposed_java";
    /** C 解析 DexFile 类表，再通过目标 ClassLoader 主动加载。 */
    public static final String ACTIVE_CALL_MODE_XPOSED_C = "xposed_c";
    /** 兼容旧配置：旧 Xposed 模式等价于 Java 模式。 */
    public static final String ACTIVE_CALL_MODE_XPOSED = ACTIVE_CALL_MODE_XPOSED_JAVA;

    /** 设置 SharedPreferences 名（与 SettingsFragment 一致） */
    private static final String SETTINGS_PREFS = "cDumpDex_settings";
    /** 主动调用模式集合的存储 key */
    private static final String KEY_ACTIVE_CALL_MODES = "active_call_modes";

    /** 兼容旧版本的旧 key */
    private static final String LEGACY_KEY_ACTIVE_LOAD_CLASS = "active_load_class";

    private SharedPreferences settingsPrefs() {
        return context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 获取已勾选的主动调用模式集合。
     * 若用户从未在新 UI 中保存过，则从旧的两个布尔值迁移读取。
     */
    public Set<String> getActiveCallModes() {
        SharedPreferences prefs = settingsPrefs();
        if (prefs.contains(KEY_ACTIVE_CALL_MODES)) {
            Set<String> stored = prefs.getStringSet(KEY_ACTIVE_CALL_MODES, null);
            Set<String> normalized = new HashSet<>();
            if (stored != null) {
                if (stored.contains(ACTIVE_CALL_MODE_XPOSED_C)) {
                    normalized.add(ACTIVE_CALL_MODE_XPOSED_C);
                } else if (stored.contains(ACTIVE_CALL_MODE_XPOSED_JAVA) || stored.contains("xposed")) {
                    normalized.add(ACTIVE_CALL_MODE_XPOSED_JAVA);
                }
            }
            return normalized;
        }
        // 旧配置迁移
        Set<String> migrated = new HashSet<>();
        if (prefs.getBoolean(LEGACY_KEY_ACTIVE_LOAD_CLASS, true)) migrated.add(ACTIVE_CALL_MODE_XPOSED_JAVA);
        return migrated;
    }

    /**
     * 保存已勾选的主动调用模式集合，并同步刷新旧的两个布尔值，
     * 以兼容 MainHook / FloatingWindowService / HomeFragment 等已有读取路径。
     *
     * @return 是否为有效组合（至少包含 Xposed/Root 中的合法值或为空集合）
     */
    public boolean setActiveCallModes(Set<String> modes) {
        if (modes == null) modes = new HashSet<>();
        // 校验：只允许已知模式
        Set<String> valid = new HashSet<>();
        for (String m : modes) {
            if (ACTIVE_CALL_MODE_XPOSED_JAVA.equals(m) || ACTIVE_CALL_MODE_XPOSED_C.equals(m)) {
                valid.add(m);
            }
        }
        // 若传入了非法值则视为无效组合
        if (valid.size() != modes.size() || valid.size() > 1) {
            return false;
        }
        SharedPreferences.Editor editor = settingsPrefs().edit();
        editor.putStringSet(KEY_ACTIVE_CALL_MODES, valid);
        // 同步旧布尔值
        editor.putBoolean(LEGACY_KEY_ACTIVE_LOAD_CLASS, !valid.isEmpty());
        editor.apply();
        return true;
    }

    /** 是否启用 Xposed 模式主动调用 */
    public boolean isXposedActiveCallEnabled() {
        return !getActiveCallModes().isEmpty();
    }

    public String getXposedActiveCallEngine() {
        Set<String> modes = getActiveCallModes();
        return modes.contains(ACTIVE_CALL_MODE_XPOSED_C) ? "c" : "java";
    }

}
