package com.hioas.sms.expr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hioas.sms.core.HioasSmsException;

import java.util.Map;

/**
 * 全局共享的 Jackson 入口。
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new HioasSmsException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /** 解析为 Map / List / 标量。 */
    public static Object parse(String text) {
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (Exception e) {
            throw new HioasSmsException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> parseObject(String text) {
        try {
            return MAPPER.readValue(text, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new HioasSmsException("JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
