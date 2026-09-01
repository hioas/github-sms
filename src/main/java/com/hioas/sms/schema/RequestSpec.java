package com.hioas.sms.schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求构造（标准 §7.3）。
 */
public class RequestSpec {

    public String method = "POST";
    public String url;
    public Map<String, String> headers = new LinkedHashMap<>();
    public Map<String, Object> query = new LinkedHashMap<>();
    public BodySpec body;
}
