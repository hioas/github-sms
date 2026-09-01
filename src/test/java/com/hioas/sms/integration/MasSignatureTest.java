package com.hioas.sms.integration;

import com.hioas.sms.HioasSms;
import com.hioas.sms.TestKit;
import com.hioas.sms.core.SmsChannel;
import com.hioas.sms.expr.Json;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 移动云MAS 交叉校验：字段按序拼接取 MD5，整体 JSON Base64 封装。
 */
class MasSignatureTest {

    private static final String INSTANCE = """
            { "schema": "hioas-instance/1.0", "channel": "mas", "configId": "mas-1",
              "config": { "ecName": "EC01", "sdkAppId": "AP01", "accessKeySecret": "SK01",
                          "signature": "移动云", "addSerial": "01", "templateId": "TP01" } }
            """;

    private static String md5(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void tmpsubmitMacAndBase64Body() throws Exception {
        TestKit.RecordingSender sender = new TestKit.RecordingSender()
                .respond("{\"rspcod\":\"success\",\"success\":true,\"msgGroup\":\"G1\"}");
        SmsChannel ch = HioasSms.classpathChannel("channels/mas.api.json", INSTANCE,
                sender, new TestKit.FixedRuntime(1700000000L, "u-1"));

        var r = ch.sendMessage("13800138000", "TP01", Map.of("code", "88"));
        assertEquals(true, r.isSuccess());
        assertEquals("G1", r.getSmsId());
        assertEquals("http://112.35.1.155:1992/sms/tmpsubmit", sender.last().url());

        // Base64 解码 → JSON，校验 mac 与字段
        byte[] raw = sender.last().body();
        Map<?, ?> payload = (Map<?, ?>) Json.parse(new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8));
        assertEquals("EC01", payload.get("ecName"));
        assertEquals("AP01", payload.get("apId"));
        assertEquals("TP01", payload.get("templateId"));
        assertEquals("13800138000", payload.get("mobiles"));
        assertEquals("[\"88\"]", payload.get("params"));

        String concat = "EC01" + "AP01" + "SK01" + "TP01" + "13800138000" + "[\"88\"]" + "移动云" + "01";
        assertEquals(md5(concat), payload.get("mac"), "mac 与独立实现不一致");
    }
}
