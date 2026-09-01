package com.hioas.sms.schema;

/**
 * SPI 兜底声明（标准 §12）：protocol=java 时引用适配器类。
 */
public class JavaSpec {

    public String clazz;

    // 描述文件中的键名为 class
    @com.fasterxml.jackson.annotation.JsonProperty("class")
    public void setClazz(String clazz) {
        this.clazz = clazz;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("class")
    public String getClazz() {
        return clazz;
    }
}
