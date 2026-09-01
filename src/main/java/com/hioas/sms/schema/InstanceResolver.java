package com.hioas.sms.schema;

import com.hioas.sms.core.HioasSmsException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实例配置解析：校验 + 合并默认值 → 有效 config 上下文（标准 §2、§4）。
 */
public final class InstanceResolver {

    private InstanceResolver() {
    }

    public static Map<String, Object> resolve(ChannelDescriptor d, InstanceConfig inst) {
        if (!InstanceConfig.SCHEMA_VERSION.equals(inst.schema)) {
            throw new HioasSmsException("实例配置 schema 必须是 " + InstanceConfig.SCHEMA_VERSION);
        }
        if (!d.channel.equals(inst.channel)) {
            throw new HioasSmsException("实例配置渠道不匹配: 描述文件 " + d.channel + "，实例 " + inst.channel);
        }
        if (inst.configId == null || inst.configId.isBlank()) {
            throw new HioasSmsException("实例配置 configId 不能为空");
        }

        Map<String, Object> effective = new LinkedHashMap<>();
        Map<String, FieldSpec> fields = d.fields();

        for (Map.Entry<String, Object> e : inst.config.entrySet()) {
            if (!fields.containsKey(e.getKey())) {
                throw new HioasSmsException("实例配置包含未声明的字段: " + e.getKey());
            }
        }
        for (Map.Entry<String, FieldSpec> e : fields.entrySet()) {
            String name = e.getKey();
            FieldSpec f = e.getValue();
            Object value = inst.config.containsKey(name) ? inst.config.get(name) : f.defaultValue;
            if (value == null || (value instanceof String s && s.isBlank())) {
                if (f.isRequired()) {
                    throw new HioasSmsException("实例配置缺少必填字段: " + name);
                }
                continue;
            }
            checkType(name, f, value);
            checkPatterns(name, f, value);
            effective.put(name, value);
        }
        return effective;
    }

    private static void checkType(String name, FieldSpec f, Object value) {
        boolean ok = switch (f.type) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            default -> false;
        };
        if (!ok) {
            throw new HioasSmsException("配置字段 " + name + " 类型应为 " + f.type + "，实际: " + value);
        }
    }

    private static void checkPatterns(String name, FieldSpec f, Object value) {
        if (!(value instanceof String s)) {
            return;
        }
        if (f.patternStartsWith != null && !s.startsWith(f.patternStartsWith)) {
            throw new HioasSmsException("配置字段 " + name + " 必须以 '" + f.patternStartsWith + "' 开头");
        }
        if (f.patternEndsWith != null && !s.endsWith(f.patternEndsWith)) {
            throw new HioasSmsException("配置字段 " + name + " 必须以 '" + f.patternEndsWith + "' 结尾");
        }
    }
}
