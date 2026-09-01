package com.hioas.sms.schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前置请求（标准 §6）。
 */
public class PreRequestSpec {

    public RequestSpec request;
    public PreResponseSpec response;

    public static class PreResponseSpec {
        /** 捕获名 → $ 路径 */
        public Map<String, String> capture = new LinkedHashMap<>();
    }
}
