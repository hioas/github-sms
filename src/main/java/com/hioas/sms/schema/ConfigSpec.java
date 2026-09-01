package com.hioas.sms.schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置字段声明区（标准 §4）。
 */
public class ConfigSpec {

    public Map<String, FieldSpec> fields = new LinkedHashMap<>();
}
