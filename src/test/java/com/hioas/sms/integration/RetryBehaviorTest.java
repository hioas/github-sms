package com.hioas.sms.integration;

import com.hioas.sms.HioasSms;
import com.hioas.sms.TestKit;
import com.hioas.sms.core.SmsChannel;
import com.hioas.sms.core.SmsResult;
import com.hioas.sms.http.HttpExchange;
import com.hioas.sms.http.HttpOptions;
import com.hioas.sms.http.HttpResult;
import com.hioas.sms.http.HttpSender;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎行为测试：失败重试（标准 §11）。
 */
class RetryBehaviorTest {

    private static final String INSTANCE = """
            { "schema": "hioas-instance/1.0", "channel": "chuanglan", "configId": "cl-r",
              "config": { "baseUrl": "https://x.com/", "msgUrl": "send",
                          "accessKeyId": "A", "accessKeySecret": "B",
                          "templateId": "T", "templateName": "code" },
              "behavior": { "maxRetries": 2, "retryIntervalMs": 1 } }
            """;

    /** 前 n 次返回失败响应，之后成功。 */
    private static final class FlakeySender implements HttpSender {
        final List<HttpExchange> seen = new ArrayList<>();
        int failures;

        FlakeySender(int failures) {
            this.failures = failures;
        }

        @Override
        public HttpResult send(HttpExchange exchange, HttpOptions options) {
            seen.add(exchange);
            if (seen.size() <= failures) {
                return new HttpResult(200, "{\"code\":\"9\",\"errorMsg\":\"busy\"}");
            }
            return new HttpResult(200, "{\"code\":\"0\",\"msgid\":\"OK\"}");
        }
    }

    @Test
    void retriesUntilSuccess() {
        FlakeySender sender = new FlakeySender(2); // 前 2 次失败，第 3 次成功
        SmsChannel ch = HioasSms.classpathChannel("channels/chuanglan.api.json", INSTANCE,
                sender, new TestKit.FixedRuntime(1L, "u"));
        SmsResult r = ch.sendMessage("138", "1");
        assertTrue(r.isSuccess());
        assertEquals(3, sender.seen.size()); // 1 次原始 + 2 次重试
    }

    @Test
    void exhaustsRetriesThenFails() {
        FlakeySender sender = new FlakeySender(99); // 永远失败
        SmsChannel ch = HioasSms.classpathChannel("channels/chuanglan.api.json", INSTANCE,
                sender, new TestKit.FixedRuntime(1L, "u"));
        SmsResult r = ch.sendMessage("138", "1");
        assertFalse(r.isSuccess());
        assertEquals("busy", r.getMessage());
        assertEquals(3, sender.seen.size()); // 重试耗尽
    }
}
