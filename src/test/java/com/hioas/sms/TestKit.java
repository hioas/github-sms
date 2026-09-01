package com.hioas.sms;

import com.hioas.sms.expr.RuntimeSource;
import com.hioas.sms.http.HttpExchange;
import com.hioas.sms.http.HttpOptions;
import com.hioas.sms.http.HttpResult;
import com.hioas.sms.http.HttpSender;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试工具：记录型 HttpSender + 确定性运行时来源。
 */
public final class TestKit {

    private TestKit() {
    }

    /** 记录所有请求并返回预设响应。 */
    public static final class RecordingSender implements HttpSender {
        public final List<HttpExchange> exchanges = new ArrayList<>();
        public String responseBody = "{}";
        public int status = 200;

        public RecordingSender respond(String body) {
            this.responseBody = body;
            return this;
        }

        public RecordingSender respond(int status, String body) {
            this.status = status;
            this.responseBody = body;
            return this;
        }

        @Override
        public HttpResult send(HttpExchange exchange, HttpOptions options) {
            exchanges.add(exchange);
            return new HttpResult(status, responseBody);
        }

        public HttpExchange last() {
            return exchanges.get(exchanges.size() - 1);
        }

        public String lastBodyText() {
            byte[] b = last().body();
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }
    }

    /** 确定性时钟与随机源，保证签名可复现。 */
    public static final class FixedRuntime implements RuntimeSource {
        private final long epochSecond;
        private final String uuid;

        public FixedRuntime(long epochSecond, String uuid) {
            this.epochSecond = epochSecond;
            this.uuid = uuid;
        }

        @Override
        public long epochSecond() {
            return epochSecond;
        }

        @Override
        public long epochMilli() {
            return epochSecond * 1000;
        }

        @Override
        public String uuid() {
            return uuid;
        }

        @Override
        public String randomDigits(int n) {
            return "1".repeat(n);
        }

        @Override
        public String randomAlnum(int n) {
            return "a".repeat(n);
        }
    }

    /** 读取 classpath 描述文件原文。 */
    public static String resource(String path) {
        try (var in = TestKit.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("classpath 未找到: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
