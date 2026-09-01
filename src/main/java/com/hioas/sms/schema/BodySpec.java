package com.hioas.sms.schema;

/**
 * 请求体定义（标准 §7.3.2）。
 */
public class BodySpec {

    /** json | form | raw */
    public String contentType = "json";
    /** none | base64 | urlEncode */
    public String encoding = "none";
    /** 序列化字符集，缺省 UTF-8（如 gbk） */
    public String charset;
    /** json/form：map 模板；raw：字符串模板；允许整体为 ${derive.x} 表达式字符串 */
    public Object template;
}
