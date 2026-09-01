package com.hioas.sms.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 JDK HttpClient 的发送实现（无第三方依赖）。
 */
public final class JdkHttpSender implements HttpSender {

    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

    @Override
    public HttpResult send(HttpExchange exchange, HttpOptions options) throws IOException {
        HttpClient client = clients.computeIfAbsent(cacheKey(options), k -> build(options));
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(exchange.url()))
                .timeout(Duration.ofMillis(options.timeoutMs()));
        exchange.headers().forEach(rb::header);
        HttpRequest.BodyPublisher publisher = exchange.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(exchange.body());
        rb.method(exchange.method(), publisher);
        try {
            HttpResponse<String> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(resp.statusCode(), resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP 请求被中断: " + exchange.url(), e);
        }
    }

    private static String cacheKey(HttpOptions o) {
        return o.timeoutMs() + "|" + o.proxyHost() + "|" + o.proxyPort();
    }

    private static HttpClient build(HttpOptions o) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(o.timeoutMs()));
        if (o.proxyHost() != null && o.proxyPort() != null) {
            b.proxy(ProxySelector.of(new InetSocketAddress(o.proxyHost(), o.proxyPort())));
        }
        return b.build();
    }
}
