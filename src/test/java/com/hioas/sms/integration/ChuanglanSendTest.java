package com.hioas.sms.integration;

import com.hioas.sms.HioasSms;
import com.hioas.sms.TestKit;
import com.hioas.sms.core.SmsChannel;
import com.hioas.sms.core.SmsResult;
import com.hioas.sms.expr.Json;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 级渠道（创蓝）：明文凭证、JSON body、单发/群发参数拼接。
 */
class ChuanglanSendTest {

    private static final String INSTANCE = """
            { "schema": "hioas-instance/1.0", "channel": "chuanglan", "configId": "cl-1",
              "config": { "baseUrl": "https://smssh1.253.com/msg/", "msgUrl": "variable/json",
                          "accessKeyId": "N123", "accessKeySecret": "P456",
                          "templateId": "T100", "templateName": "code" } }
            """;

    @Test
    void singleSendBuildsParamsPair() {
        TestKit.RecordingSender sender = new TestKit.RecordingSender()
                .respond("{\"code\":\"0\",\"msgid\":\"M1\"}");
        SmsChannel ch = HioasSms.classpathChannel("channels/chuanglan.api.json", INSTANCE,
                sender, new TestKit.FixedRuntime(1700000000L, "u-1"));

        SmsResult r = ch.sendMessage("13800138000", "1234");
        assertTrue(r.isSuccess());
        assertEquals("M1", r.getSmsId());

        Map<?, ?> body = (Map<?, ?>) Json.parse(sender.lastBodyText());
        assertEquals("N123", body.get("account"));
        assertEquals("P456", body.get("password"));
        assertEquals("T100", body.get("msg"));
        // params = 手机号,内容
        assertEquals("13800138000,1234", body.get("params"));
        assertEquals("https://smssh1.253.com/msg/variable/json", sender.last().url());
    }

    @Test
    void massSendPairsEachPhone() {
        TestKit.RecordingSender sender = new TestKit.RecordingSender()
                .respond("{\"code\":\"0\",\"msgid\":\"M2\"}");
        SmsChannel ch = HioasSms.classpathChannel("channels/chuanglan.api.json", INSTANCE,
                sender, new TestKit.FixedRuntime(1700000000L, "u-1"));

        SmsResult r = ch.massTexting(java.util.List.of("138", "139"), "T100", Map.of("code", "9"));
        assertTrue(r.isSuccess());

        Map<?, ?> body = (Map<?, ?>) Json.parse(sender.lastBodyText());
        // 每个手机号配同一内容，以 ; 连接
        assertEquals("138,9;139,9", body.get("params"));
    }
}
