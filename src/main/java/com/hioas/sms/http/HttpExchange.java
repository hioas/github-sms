package com.hioas.sms.http;

import java.util.Map;

/**
 * 一次 HTTP 调用（渲染完成后的最终形态）。
 */
public record HttpExchange(String method, String url, Map<String, String> headers, byte[] body) {
}
