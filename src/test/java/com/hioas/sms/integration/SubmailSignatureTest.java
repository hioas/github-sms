package com.hioas.sms.integration;

import com.hioas.sms.HioasSms;
import com.hioas.sms.TestKit;
import com.hioas.sms.core.SmsChannel;
import com.hioas.sms.expr.Json;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 赛邮交叉校验：前置请求取服务端时间戳 + appid/secret 包夹排序参数的 MD5 签名。
 */
class SubmailSignatureTest {

    private static final String INSTANCE = """
            { "schema": "hioas-instance/1.0", "channel": "submail", "configId": "sm-1",
              "config": { "accessKeyId": "APP01", "accessKeySecret": "SEC01",
                          "signature": "赛邮", "templateId": "P01" } }
            """;

    private static String md5(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void xsendUsesServerTimestampAndEnvelopeSignature() throws Exception {
        // 所有请求统一返回带服务端时间戳的响应：前置请求捕获 ts，主请求解析为失败（不影响请求校验）
        TestKit.RecordingSender sender = new TestKit.RecordingSender()
                .respond("{\"timestamp\":\"1699999999\",\"status\":\"waiting\"}");
        SmsChannel ch = HioasSms.classpathChannel("channels/submail.api.json", INSTANCE,
                sender, new TestKit.FixedRuntime(1700000000L, "u-1"));

        ch.sendMessage("13800138000", "P01", Map.of("code", "42"));

        // 1 次前置 + 1 次主请求
        assertEquals(2, sender.exchanges.size());
        assertEquals("https://api-v4.mysubmail.com/service/timestamp", sender.exchanges.get(0).url());

        Map<?, ?> body = (Map<?, ?>) Json.parse(sender.lastBodyText());
        assertEquals("APP01", body.get("appid"));
        assertEquals("+8613800138000", body.get("to"));
        assertEquals("P01", body.get("project"));
        assertEquals("1699999999", body.get("timestamp"), "必须使用前置请求的服务端时间戳");
        assertEquals(Json.toJson(Map.of("code", "42")), body.get("vars"));

        // 独立重算签名：排除 vars 后的排序参数
        TreeMap<String, String> signSource = new TreeMap<>();
        signSource.put("appid", "APP01");
        signSource.put("to", "+8613800138000");
        signSource.put("project", "P01");
        signSource.put("timestamp", "1699999999");
        signSource.put("sign_type", "MD5");
        StringBuilder sb = new StringBuilder();
        signSource.forEach((k, v) -> sb.append(k).append('=').append(v).append('&'));
        String expected = md5("APP01" + "SEC01" + sb + "APP01" + "SEC01");
        assertEquals(expected, body.get("signature"), "赛邮签名与独立实现不一致");
    }
}
