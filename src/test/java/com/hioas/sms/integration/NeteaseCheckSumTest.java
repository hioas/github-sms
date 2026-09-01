package com.hioas.sms.integration;

import com.hioas.sms.HioasSms;
import com.hioas.sms.TestKit;
import com.hioas.sms.core.SmsChannel;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网易云信交叉校验：CheckSum = SHA1(secret + nonce + curTime)。
 */
class NeteaseCheckSumTest {

    private static final String INSTANCE = """
            { "schema": "hioas-instance/1.0", "channel": "netease", "configId": "ne-1",
              "config": { "templateUrl": "https://api.sms.163.com/sms/sendtemplate.action",
                          "accessKeyId": "AK01", "accessKeySecret": "SK01", "templateId": "T01" } }
            """;

    private static String sha1Hex(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void checkSumHeaderMatches() throws Exception {
        TestKit.RecordingSender sender = new TestKit.RecordingSender()
                .respond("{\"code\":200}");
        TestKit.FixedRuntime rt = new TestKit.FixedRuntime(1700000000L, "nonce-uuid-0001");
        SmsChannel ch = HioasSms.classpathChannel("channels/netease.api.json", INSTANCE, sender, rt);

        java.util.LinkedHashMap<String, String> vars = new java.util.LinkedHashMap<>();
        vars.put("p1", "a");
        vars.put("p2", "b");
        var r = ch.sendMessage("13800138000", "T01", vars);
        assertTrue(r.isSuccess());

        Map<String, String> headers = sender.last().headers();
        String nonce = headers.get("Nonce");
        String curTime = headers.get("CurTime");
        assertEquals("nonce-uuid-0001", nonce);
        assertEquals("1700000000", curTime);
        assertEquals("AK01", headers.get("AppKey"));
        assertEquals(sha1Hex("SK01" + nonce + curTime), headers.get("CheckSum"), "CheckSum 与独立实现不一致");

        // form body：号码/变量为 JSON 数组字符串
        String body = sender.lastBodyText();
        Map<String, String> form = parseForm(body);
        assertEquals("T01", form.get("templateid"));
        assertEquals("[\"13800138000\"]", form.get("mobiles"));
        assertEquals("[\"a\",\"b\"]", form.get("params"));
    }

    private static Map<String, String> parseForm(String form) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }
}
