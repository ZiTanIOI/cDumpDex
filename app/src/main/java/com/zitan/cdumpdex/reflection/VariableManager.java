package com.zitan.cdumpdex.reflection;

import android.content.Context;
import android.util.Log;

import com.zitan.cdumpdex.RetraceableVar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * 变量管理器
 * 负责管理所有变量的生命周期、序列化和反序列化
 */
public class VariableManager {
    private static final String TAG = "VariableManager";
    private static final String VAR_DIR_NAME = "cdumpdex_Var";
    private static final String VAR_FILE_NAME = "variables.json";
    private static final int VERSION = 1;

    private final LinkedHashMap<String, RetraceableVar> variables = new LinkedHashMap<>();
    private final Context context;
    private final File varDir;
    private final File varFile;

    // 实例扫描缓存（类名 -> 实例列表）
    private final Map<String, List<WeakReference<Object>>> instanceCache = new HashMap<>();
    // 已 hook 的类集合
    private final Set<String> hookedClasses = Collections.synchronizedSet(new HashSet<>());

    private static VariableManager instance;

    public static synchronized VariableManager getInstance(Context context) {
        if (instance == null && context != null) {
            try {
                instance = new VariableManager(context);
            } catch (Exception e) {
                android.util.Log.e(TAG, "Failed to create VariableManager", e);
            }
        }
        return instance;
    }

    private VariableManager(Context context) {
        // 使用传入的 context，如果 getApplicationContext() 返回 null 则使用原始 context
        Context appContext = context.getApplicationContext();
        this.context = (appContext != null) ? appContext : context;

        // 使用正确的存储路径
        File externalDir = this.context.getExternalFilesDir(null);
        if (externalDir != null) {
            // 使用应用专属存储目录：/storage/emulated/0/Android/data/{package}/files/cdumpdex_Var
            this.varDir = new File(externalDir, VAR_DIR_NAME);
        } else {
            // 备用：使用内部存储
            this.varDir = new File(this.context.getFilesDir(), VAR_DIR_NAME);
        }
        this.varFile = new File(varDir, VAR_FILE_NAME);

        // 确保目录存在
        try {
            if (!varDir.exists()) {
                varDir.mkdirs();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create varDir: " + e.getMessage());
        }

        // 启动时加载已保存的变量
        try {
            loadFromFile();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load variables: " + e.getMessage());
        }
    }

    /**
     * 添加变量
     */
    public void addVariable(String name, RetraceableVar var) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("变量名不能为空");
        }
        var.setVarName(name);
        variables.put(name, var);
        saveToFile();
    }

    /**
     * 添加变量（自动生成名称）
     */
    public String addVariable(RetraceableVar var) {
        String baseName = generateVarName(var);
        String name = baseName;
        int counter = 1;
        while (variables.containsKey(name)) {
            name = baseName + "_" + counter++;
        }
        var.setVarName(name);
        variables.put(name, var);
        saveToFile();
        return name;
    }

    /**
     * 生成变量名
     */
    private String generateVarName(RetraceableVar var) {
        String typeName = var.getTypeDisplayName();
        String baseName = Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);
        // 简化名称
        int lastDot = baseName.lastIndexOf('.');
        if (lastDot >= 0) {
            baseName = baseName.substring(lastDot + 1);
        }
        // 移除特殊字符
        baseName = baseName.replaceAll("[^a-zA-Z0-9_]", "_");
        return baseName;
    }

    /**
     * 移除变量
     */
    public void removeVariable(String name) {
        variables.remove(name);
        saveToFile();
    }

    /**
     * 获取变量
     */
    public RetraceableVar getVariable(String name) {
        return variables.get(name);
    }

    /**
     * 获取变量的值
     */
    public Object getVariableValue(String name) {
        RetraceableVar var = variables.get(name);
        return var != null ? var.getValue() : null;
    }

    /**
     * 获取所有变量名
     */
    public List<String> getVariableNames() {
        return new ArrayList<>(variables.keySet());
    }

    /**
     * 获取所有变量
     */
    public Map<String, RetraceableVar> getAllVariables() {
        return new LinkedHashMap<>(variables);
    }

    /**
     * 检查变量是否存在
     */
    public boolean hasVariable(String name) {
        return variables.containsKey(name);
    }

    /**
     * 清除所有变量
     */
    public void clearAll() {
        variables.clear();
        saveToFile();
    }

    /**
     * 清理无效变量（值已被 GC）
     */
    public void cleanupInvalidVariables() {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, RetraceableVar> entry : variables.entrySet()) {
            if (!entry.getValue().isValueValid() &&
                entry.getValue().getSource() != RetraceableVar.VarSource.PRIMITIVE) {
                toRemove.add(entry.getKey());
            }
        }
        for (String name : toRemove) {
            variables.remove(name);
        }
        if (!toRemove.isEmpty()) {
            saveToFile();
        }
    }

    // ==================== 序列化相关 ====================

    /**
     * 保存到文件
     */
    public void saveToFile() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("packageName", context.getPackageName());

            JSONArray varsArray = new JSONArray();
            for (RetraceableVar var : variables.values()) {
                try {
                    varsArray.put(var.toJson());
                } catch (JSONException e) {
                    Log.w(TAG, "Failed to serialize variable: " + var.getVarName(), e);
                }
            }
            root.put("variables", varsArray);

            // 写入文件
            try (FileWriter writer = new FileWriter(varFile)) {
                writer.write(root.toString(2));
            }

        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to save variables", e);
        }
    }

    /**
     * 从文件加载
     */
    public void loadFromFile() {
        if (!varFile.exists()) {
            return;
        }

        try (FileReader reader = new FileReader(varFile)) {
            char[] buffer = new char[(int) varFile.length()];
            reader.read(buffer);
            String content = new String(buffer);

            JSONObject root = new JSONObject(content);
            int version = root.optInt("version", 0);

            if (version != VERSION) {
                Log.w(TAG, "Version mismatch, expected " + VERSION + " got " + version);
                return;
            }

            JSONArray varsArray = root.optJSONArray("variables");
            if (varsArray == null) return;

            for (int i = 0; i < varsArray.length(); i++) {
                try {
                    JSONObject varJson = varsArray.getJSONObject(i);
                    RetraceableVar var = RetraceableVar.fromJson(varJson);
                    if (var != null && var.getVarName() != null) {
                        variables.put(var.getVarName(), var);
                    }
                } catch (JSONException e) {
                    Log.w(TAG, "Failed to deserialize variable at index " + i, e);
                }
            }

        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to load variables", e);
        }
    }

    /**
     * 重建变量（按依赖顺序重新创建对象实例）
     * 需要在类加载器准备好后调用
     */
    public void rebuildVariables(ClassLoader classLoader) {
        // 获取依赖顺序
        List<String> order = getDependencyOrder();

        for (String varName : order) {
            RetraceableVar var = variables.get(varName);
            if (var == null || var.getValue() != null) continue;

            try {
                rebuildVariable(var, classLoader);
            } catch (Exception e) {
                Log.e(TAG, "Failed to rebuild variable: " + varName, e);
            }
        }
    }

    /**
     * 获取依赖顺序（拓扑排序）
     */
    private List<String> getDependencyOrder() {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String varName : variables.keySet()) {
            visitDependency(varName, visited, visiting, result);
        }

        return result;
    }

    /**
     * DFS 遍历依赖
     */
    private void visitDependency(String varName, Set<String> visited, Set<String> visiting, List<String> result) {
        if (visited.contains(varName)) return;
        if (visiting.contains(varName)) {
            Log.w(TAG, "Circular dependency detected: " + varName);
            return;
        }

        visiting.add(varName);
        RetraceableVar var = variables.get(varName);
        if (var != null) {
            // 检查构造函数参数依赖
            if (var.getParamVarRefs() != null) {
                for (String depName : var.getParamVarRefs().values()) {
                    if (depName != null && variables.containsKey(depName)) {
                        visitDependency(depName, visited, visiting, result);
                    }
                }
            }
            // 检查方法实例依赖
            if (var.getInstanceVarRef() != null && variables.containsKey(var.getInstanceVarRef())) {
                visitDependency(var.getInstanceVarRef(), visited, visiting, result);
            }
            // 检查字段实例依赖
            if (var.getFieldInstanceRef() != null && variables.containsKey(var.getFieldInstanceRef())) {
                visitDependency(var.getFieldInstanceRef(), visited, visiting, result);
            }
        }
        visiting.remove(varName);
        visited.add(varName);
        result.add(varName);
    }

    /**
     * 重建单个变量
     */
    private void rebuildVariable(RetraceableVar var, ClassLoader classLoader) throws Exception {
        switch (var.getSource()) {
            case PRIMITIVE:
                // 基本类型已经在 fromJson 中恢复
                break;

            case CONTEXT:
                // Context 需要重新获取
                // 这需要在外部处理
                break;

            case CONSTRUCTOR:
                rebuildFromConstructor(var, classLoader);
                break;

            case METHOD_RETURN:
                rebuildFromMethod(var, classLoader);
                break;

            case FIELD_ACCESS:
            case STATIC_FIELD:
                rebuildFromField(var, classLoader);
                break;

            case INSTANCE_SCAN:
                // 实例扫描结果无法重建
                break;
        }
    }

    /**
     * 从构造函数重建
     */
    private void rebuildFromConstructor(RetraceableVar var, ClassLoader classLoader) throws Exception {
        Class<?> clazz = classLoader.loadClass(var.getType().getName());

        // 构建参数数组
        int maxIndex = 0;
        if (var.getParamVarRefs() != null) {
            for (Integer idx : var.getParamVarRefs().keySet()) {
                maxIndex = Math.max(maxIndex, idx);
            }
        }
        if (var.getPrimitiveParams() != null) {
            for (Integer idx : var.getPrimitiveParams().keySet()) {
                maxIndex = Math.max(maxIndex, idx);
            }
        }

        if (maxIndex == 0) {
            // 无参构造函数
            var.setValue(clazz.newInstance());
            return;
        }

        // 构建参数值数组
        Object[] args = new Object[maxIndex + 1];
        Class<?>[] argTypes = new Class<?>[maxIndex + 1];

        // 填充参数引用
        if (var.getParamVarRefs() != null) {
            for (Map.Entry<Integer, String> entry : var.getParamVarRefs().entrySet()) {
                int idx = entry.getKey();
                String refName = entry.getValue();
                if (refName != null) {
                    Object depValue = getVariableValue(refName);
                    args[idx] = depValue;
                    argTypes[idx] = depValue != null ? depValue.getClass() : Object.class;
                }
            }
        }

        // 填充基本类型参数
        if (var.getPrimitiveParams() != null) {
            for (Map.Entry<Integer, Object> entry : var.getPrimitiveParams().entrySet()) {
                int idx = entry.getKey();
                Object val = entry.getValue();
                args[idx] = val;
                argTypes[idx] = val != null ? val.getClass() : Object.class;
            }
        }

        // 查找匹配的构造函数
        for (java.lang.reflect.Constructor<?> ctor : clazz.getConstructors()) {
            Class<?>[] paramTypes = ctor.getParameterTypes();
            if (paramTypes.length != argTypes.length) continue;

            boolean match = true;
            for (int i = 0; i < paramTypes.length; i++) {
                if (args[i] == null) {
                    // null 可以匹配任何引用类型
                    if (paramTypes[i].isPrimitive()) {
                        match = false;
                        break;
                    }
                } else if (!paramTypes[i].isAssignableFrom(argTypes[i])) {
                    // 尝试基本类型转换
                    if (!isAssignableWithPrimitive(paramTypes[i], argTypes[i])) {
                        match = false;
                        break;
                    }
                }
            }

            if (match) {
                ctor.setAccessible(true);
                var.setValue(ctor.newInstance(args));
                return;
            }
        }

        throw new Exception("No matching constructor found for " + clazz.getName());
    }

    /**
     * 检查类型是否可分配（考虑基本类型）
     */
    private boolean isAssignableWithPrimitive(Class<?> targetType, Class<?> sourceType) {
        if (targetType.isAssignableFrom(sourceType)) return true;

        // 基本类型与包装类匹配
        if (targetType.isPrimitive()) {
            return isWrapperOf(sourceType, targetType);
        }
        if (sourceType.isPrimitive()) {
            return isWrapperOf(targetType, sourceType);
        }
        return false;
    }

    private boolean isWrapperOf(Class<?> wrapper, Class<?> primitive) {
        if (primitive == int.class) return wrapper == Integer.class;
        if (primitive == long.class) return wrapper == Long.class;
        if (primitive == double.class) return wrapper == Double.class;
        if (primitive == float.class) return wrapper == Float.class;
        if (primitive == boolean.class) return wrapper == Boolean.class;
        if (primitive == byte.class) return wrapper == Byte.class;
        if (primitive == short.class) return wrapper == Short.class;
        if (primitive == char.class) return wrapper == Character.class;
        return false;
    }

    /**
     * 从方法调用重建
     */
    private void rebuildFromMethod(RetraceableVar var, ClassLoader classLoader) throws Exception {
        String className = var.getMethodClassName();
        String methodName = var.getMethodName();
        String instanceRef = var.getInstanceVarRef();

        if (className == null || methodName == null) {
            throw new Exception("Missing method info");
        }

        Class<?> clazz = classLoader.loadClass(className);

        // 获取实例对象（null 表示静态方法）
        Object instance = null;
        if (instanceRef != null) {
            instance = getVariableValue(instanceRef);
        }

        // 查找方法（目前支持无参方法）
        for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == 0) {
                method.setAccessible(true);
                Object result = method.invoke(instance);
                var.setValue(result);
                return;
            }
        }

        throw new Exception("Method not found: " + className + "." + methodName + "()");
    }

    /**
     * 从字段访问重建
     */
    private void rebuildFromField(RetraceableVar var, ClassLoader classLoader) throws Exception {
        String className = var.getFieldClassName();
        String fieldName = var.getFieldName();
        String instanceRef = var.getFieldInstanceRef();

        if (className == null || fieldName == null) {
            throw new Exception("Missing field info");
        }

        Class<?> clazz = classLoader.loadClass(className);

        // 获取实例对象（null 表示静态字段）
        Object instance = null;
        if (instanceRef != null) {
            instance = getVariableValue(instanceRef);
        }

        // 获取字段
        java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(instance);
        var.setValue(value);
    }

    // ==================== 实例缓存相关 ====================

    /**
     * 获取实例缓存
     */
    public Map<String, List<WeakReference<Object>>> getInstanceCache() {
        return instanceCache;
    }

    /**
     * 添加实例到缓存
     */
    public void addInstanceToCache(String className, Object instance) {
        List<WeakReference<Object>> list = instanceCache.computeIfAbsent(className, k -> new ArrayList<>());
        list.add(new WeakReference<>(instance));
    }

    /**
     * 获取类的存活实例
     */
    public List<Object> getLiveInstances(String className) {
        List<WeakReference<Object>> refs = instanceCache.get(className);
        if (refs == null) return Collections.emptyList();

        List<Object> liveInstances = new ArrayList<>();
        List<WeakReference<Object>> toRemove = new ArrayList<>();

        for (WeakReference<Object> ref : refs) {
            Object obj = ref.get();
            if (obj != null) {
                liveInstances.add(obj);
            } else {
                toRemove.add(ref);
            }
        }

        // 清理已被 GC 的引用
        refs.removeAll(toRemove);

        return liveInstances;
    }

    /**
     * 检查类是否已被 hook
     */
    public boolean isClassHooked(String className) {
        return hookedClasses.contains(className);
    }

    /**
     * 标记类为已 hook
     */
    public void markClassHooked(String className) {
        hookedClasses.add(className);
    }

    /**
     * 获取已 hook 的类集合
     */
    public Set<String> getHookedClasses() {
        return new HashSet<>(hookedClasses);
    }
}
