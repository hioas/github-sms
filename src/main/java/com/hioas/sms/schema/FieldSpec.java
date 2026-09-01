package com.hioas.sms.schema;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * config.fields 单字段声明（标准 §4）。
 */
public class FieldSpec {

    public String type = "string";
    public Boolean required;
    public Boolean sensitive;
    @JsonProperty("default")
    public Object defaultValue;
    public String label;
    public String description;
    public String patternStartsWith;
    public String patternEndsWith;

    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }

    public boolean isSensitive() {
        return Boolean.TRUE.equals(sensitive);
    }
}
