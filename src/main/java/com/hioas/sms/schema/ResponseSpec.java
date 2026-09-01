package com.hioas.sms.schema;

/**
 * 响应解析（标准 §9）。
 */
public class ResponseSpec {

    /** json | text */
    public String contentType = "json";
    public String successWhen;
    /** $ 路径 */
    public String smsId;
    /** $ 路径 */
    public String message;
}
