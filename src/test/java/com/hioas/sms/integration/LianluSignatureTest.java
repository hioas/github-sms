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
 * 联麓签名交叉校验：排序参数拼接 + &key= 后 MD5 大写。
 */
class LianluSignatureTest {

    private static final String INSTANCE = """
            { "schema": "hioas-instance/1.0", "channel": "lianlu", "configId": "ll-1",
              "config": { "mchId": "M001", "appId": "A001", "appKey": "KEY001",
                          "signature": "测试签名", "templateId": "T001" } }
            """;

    private static String md5Upper(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8))).toUpperCase();
    }

    @Test
    void templateSendSignatureMatches() throws Exception {
        TestKit.RecordingSender sender = new TestKit.RecordingSender().respond("{\"status\":\"00\"}");
        SmsChannel ch = HioasSms.classpathChannel("channels/lianlu.api.json", INSTANCE,
                sender, new TestKit.FixedRuntime(1700000000L, "u-1"));

        ch.sendMessage("13800138000", "T001", Map.of("code", "77"));
        Map<?, ?> body = (Map<?, ?>) Json.parse(sender.lastBodyText());

        // 独立重算：参与签名的字段（排除 PhoneNumberSet/TemplateParamSet/Signature）
        TreeMap<String, String> signParams = new TreeMap<>();
        signParams.put("MchId", "M001");
        signParams.put("AppId", "A001");
        signParams.put("SignType", "MD5");
        signParams.put("TimeStamp", String.valueOf(body.get("TimeStamp")));
        signParams.put("Type", "3");
        signParams.put("Version", "1.1.0");
        signParams.put("TemplateId", "T001");
        signParams.put("SignName", "测试签名");
        StringBuilder sb = new StringBuilder();
        signParams.forEach((k, v) -> sb.append(k).append('=').append(v).append('&'));
        sb.append("key=KEY001");
        String expected = md5Upper(sb.toString());

        assertEquals(expected, body.get("Signature"), "联麓签名与独立实现不一致");
        assertEquals("77", ((java.util.List<?>) body.get("TemplateParamSet")).get(0));
        assertEquals("13800138000", ((java.util.List<?>) body.get("PhoneNumberSet")).get(0));
    }
}
