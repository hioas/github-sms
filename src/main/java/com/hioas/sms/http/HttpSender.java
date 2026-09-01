package com.hioas.sms.http;

import java.io.IOException;

/**
 * HTTP 发送抽象：生产实现为 JDK HttpClient，测试可注入记录型实现。
 */
public interface HttpSender {

    HttpResult send(HttpExchange exchange, HttpOptions options) throws IOException;
}
