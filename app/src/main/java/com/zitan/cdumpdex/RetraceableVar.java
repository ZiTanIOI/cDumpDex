package com.zitan.cdumpdex;

import android.content.Context;
import android.view.View;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可追溯的变量包装类
 * 支持序列化、多种来源和类型
 */
public class RetraceableVar {

    /**
     * 变量来源类型
     */
    public enum VarSource {
        CONTEXT,          // 来自 UIContext
        PRIMITIVE,        // 基本类型
        CONSTRUCTOR,      // 通过构造函数创建
        METHOD_RETURN,    // 方法返回值
        INSTANCE_SCAN,    // 内存扫描获取
        FIELD_ACCESS,     // 字段访问获取
        STATIC_FIELD      // 静态字段
    }

    private String varName;           // 变量名
    private Object value;             // 实际值
    private Class<?> type;            // 类型
    private VarSource source;         // 来源
    private long createTime;          // 创建时间

    // 构造函数参数引用（如果是 CONSTRUCTOR 来源）
    private LinkedHashMap<Integer, String> paramVarRefs;  // 参数索引 -> 变量名引用
    private LinkedHashMap<Integer, Object> primitiveParams; // 基本类型参数值

    // 方法调用信息（如果是 METHOD_RETURN 来源）
    private String methodClassName;
    private String methodName;
    private String instanceVarRef;    // 实例变量引用（null 表示静态方法）

    // 字段访问信息（如果是 FIELD_ACCESS 来源）
    private String fieldClassName;
    private String fieldName;
    private String fieldInstanceRef;  // 实例变量引用（null 表示静态字段）

    // 弱引用避免内存泄漏
    private WeakReference<Object> weakValue;

    public RetraceableVar(String name, Object value, VarSource source) {
        this.varName = name;
        this.value = value;
        this.type = value != null ? value.getClass() : Object.class;
        this.source = source;
        this.createTime = System.currentTimeMillis();
        this.weakValue = new WeakReference<>(value);
    }

    public RetraceableVar(String name, Object value, VarSource source, Class<?> declaredType) {
        this.varName = name;
        this.value = value;
        this.type = declaredType != null ? declaredType : (value != null ? value.getClass() : Object.class);
        this.source = source;
        this.createTime = System.currentTimeMillis();
        this.weakValue = new WeakReference<>(value);
    }

    // Getters
    public String getVarName() { return varName; }
    public Object getValue() {
        // 优先从弱引用获取，如果被 GC 则返回原值
        Object weakVal = weakValue != null ? weakValue.get() : null;
        return weakVal != null ? weakVal : value;
    }
    public Class<?> getType() { return type; }
    public VarSource getSource() { return source; }
    public long getCreateTime() { return createTime; }

    // Setters
    public void setVarName(String varName) { this.varName = varName; }
    public void setValue(Object value) {
        this.value = value;
        this.weakValue = new WeakReference<>(value);
    }

    // 构造函数参数相关
    public void setConstructorParams(LinkedHashMap<Integer, String> paramVarRefs,
                                      LinkedHashMap<Integer, Object> primitiveParams) {
        this.paramVarRefs = paramVarRefs;
        this.primitiveParams = primitiveParams;
    }

    public LinkedHashMap<Integer, String> getParamVarRefs() { return paramVarRefs; }
    public LinkedHashMap<Integer, Object> getPrimitiveParams() { return primitiveParams; }

    // 方法调用相关
    public void setMethodInfo(String className, String methodName, String instanceRef) {
        this.methodClassName = className;
        this.methodName = methodName;
        this.instanceVarRef = instanceRef;
    }

    public String getMethodClassName() { return methodClassName; }
    public String getMethodName() { return methodName; }
    public String getInstanceVarRef() { return instanceVarRef; }

    // 字段访问相关
    public void setFieldInfo(String className, String fieldName, String instanceRef) {
        this.fieldClassName = className;
        this.fieldName = fieldName;
        this.fieldInstanceRef = instanceRef;
    }

    public String getFieldClassName() { return fieldClassName; }
    public String getFieldName() { return fieldName; }
    public String getFieldInstanceRef() { return fieldInstanceRef; }

    /**
     * 获取 Context（如果值是 Context 或 View）
     */
    public Context getContext() throws IsNotContextException {
        Object val = getValue();
        if (val instanceof Context) {
            return (Context) val;
        } else if (val instanceof View) {
            return ((View) val).getContext();
        } else {
            throw new IsNotContextException("当前变量不是Context");
        }
    }

    /**
     * 检查值是否仍然有效（未被 GC）
     */
    public boolean isValueValid() {
        if (weakValue == null) return value != null;
        return weakValue.get() != null;
    }

    /**
     * 获取类型显示名称
     */
    public String getTypeDisplayName() {
        if (type == null) return "null";
        return type.getSimpleName();
    }

    /**
     * 获取值显示字符串
     */
    public String getValueDisplayString() {
        Object val = getValue();
        if (val == null) return "null";

        if (val instanceof String) {
            return "\"" + val + "\"";
        } else if (val.getClass().isArray()) {
            return type.getSimpleName() + "[" + java.lang.reflect.Array.getLength(val) + "]";
        } else if (val instanceof Number || val instanceof Boolean) {
            return String.valueOf(val);
        } else {
            return val.toString();
        }
    }

    /**
     * 序列化为 JSON
     */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("name", varName);
        json.put("type", type != null ? type.getName() : "java.lang.Object");
        json.put("source", source.name());
        json.put("createTime", createTime);

        // 根据来源保存额外信息
        switch (source) {
            case CONSTRUCTOR:
                if (paramVarRefs != null) {
                    JSONObject paramRefsJson = new JSONObject();
                    for (Map.Entry<Integer, String> entry : paramVarRefs.entrySet()) {
                        paramRefsJson.put(String.valueOf(entry.getKey()), entry.getValue() != null ? entry.getValue() : JSONObject.NULL);
                    }
                    json.put("paramVarRefs", paramRefsJson);
                }
                if (primitiveParams != null) {
                    JSONObject primParamsJson = new JSONObject();
                    for (Map.Entry<Integer, Object> entry : primitiveParams.entrySet()) {
                        primParamsJson.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    json.put("primitiveParams", primParamsJson);
                }
                break;

            case METHOD_RETURN:
                json.put("methodClassName", methodClassName);
                json.put("methodName", methodName);
                json.put("instanceVarRef", instanceVarRef != null ? instanceVarRef : JSONObject.NULL);
                break;

            case FIELD_ACCESS:
            case STATIC_FIELD:
                json.put("fieldClassName", fieldClassName);
                json.put("fieldName", fieldName);
                json.put("fieldInstanceRef", fieldInstanceRef != null ? fieldInstanceRef : JSONObject.NULL);
                break;

            case PRIMITIVE:
                // 尝试保存基本类型值
                Object val = getValue();
                if (val != null) {
                    json.put("primitiveValue", val.toString());
                    json.put("primitiveType", val.getClass().getName());
                }
                break;
        }

        return json;
    }

    /**
     * 从 JSON 反序列化（仅恢复元数据，实际值需要后续重建）
     */
    public static RetraceableVar fromJson(JSONObject json) throws JSONException {
        String name = json.getString("name");
        String typeName = json.getString("type");
        VarSource source = VarSource.valueOf(json.getString("source"));
        long createTime = json.optLong("createTime", System.currentTimeMillis());

        Class<?> type;
        try {
            type = Class.forName(typeName);
        } catch (ClassNotFoundException e) {
            type = Object.class;
        }

        RetraceableVar var = new RetraceableVar(name, null, source, type);
        var.createTime = createTime;

        // 根据来源恢复额外信息
        switch (source) {
            case CONSTRUCTOR:
                if (json.has("paramVarRefs")) {
                    JSONObject paramRefsJson = json.getJSONObject("paramVarRefs");
                    LinkedHashMap<Integer, String> refs = new LinkedHashMap<>();
                    for (String key : ReflectUtils.keys(paramRefsJson)) {
                        Object val = paramRefsJson.get(key);
                        refs.put(Integer.parseInt(key), val == JSONObject.NULL ? null : (String) val);
                    }
                    var.paramVarRefs = refs;
                }
                if (json.has("primitiveParams")) {
                    JSONObject primParamsJson = json.getJSONObject("primitiveParams");
                    LinkedHashMap<Integer, Object> primParams = new LinkedHashMap<>();
                    for (String key : ReflectUtils.keys(primParamsJson)) {
                        primParams.put(Integer.parseInt(key), primParamsJson.get(key));
                    }
                    var.primitiveParams = primParams;
                }
                break;

            case METHOD_RETURN:
                var.methodClassName = json.optString("methodClassName", null);
                var.methodName = json.optString("methodName", null);
                var.instanceVarRef = json.isNull("instanceVarRef") ? null : json.optString("instanceVarRef");
                break;

            case FIELD_ACCESS:
            case STATIC_FIELD:
                var.fieldClassName = json.optString("fieldClassName", null);
                var.fieldName = json.optString("fieldName", null);
                var.fieldInstanceRef = json.isNull("fieldInstanceRef") ? null : json.optString("fieldInstanceRef");
                break;

            case PRIMITIVE:
                String primValue = json.optString("primitiveValue", null);
                String primTypeName = json.optString("primitiveType", "java.lang.String");
                if (primValue != null) {
                    var.value = parsePrimitiveValue(primValue, primTypeName);
                    var.weakValue = new WeakReference<>(var.value);
                }
                break;
        }

        return var;
    }

    /**
     * 解析基本类型值
     */
    private static Object parsePrimitiveValue(String value, String typeName) {
        try {
            switch (typeName) {
                case "java.lang.Integer":
                case "int":
                    return Integer.parseInt(value);
                case "java.lang.Long":
                case "long":
                    return Long.parseLong(value);
                case "java.lang.Double":
                case "double":
                    return Double.parseDouble(value);
                case "java.lang.Float":
                case "float":
                    return Float.parseFloat(value);
                case "java.lang.Boolean":
                case "boolean":
                    return Boolean.parseBoolean(value);
                case "java.lang.Byte":
                case "byte":
                    return Byte.parseByte(value);
                case "java.lang.Short":
                case "short":
                    return Short.parseShort(value);
                case "java.lang.Character":
                case "char":
                    return value.charAt(0);
                default:
                    return value; // 默认作为字符串
            }
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 不是 Context 异常
     */
    public static class IsNotContextException extends Exception {
        public IsNotContextException(String message) {
            super(message);
        }
    }

    /**
     * 不支持的变量类型异常
     */
    public static class UnSupportVarTypeException extends Exception {
        public UnSupportVarTypeException(String message) {
            super(message);
        }
    }
}
