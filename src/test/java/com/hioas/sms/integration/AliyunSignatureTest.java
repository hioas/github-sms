package com.hioas.sms.integration;

import com.hioas.sms.HioasSms;
import com.hioas.sms.TestKit;
import com.hioas.sms.core.SmsChannel;
import com.hioas.sms.core.SmsResult;
import com.hioas.sms.expr.Json;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C 级渠道（阿里云）：POP v1 签名。用独立实现的签名算法交叉校验引擎派生链的正确性。
 */
class AliyunSignatureTest {

    private static final String AK = "testAccessKeyId";
    private static final String SK = "testAccessKeySecret";
    private static final long FIXED_TS = 1456123456L;
    private static final String FIXED_UUID = "3ee8c1b8-83d3-44af-a94f-4e0ad82fd6cf";

    private static final String INSTANCE = """
            { "schema": "hioas-instance/1.0", "channel": "aliyun", "configId": "ali-1",
              "config": { "requestUrl": "dysmsapi.aliyuncs.com",
                          "accessKeyId": "%s", "accessKeySecret": "%s",
                          "signature": "测试签名", "templateId": "SMS_100", "templateName": "code" } }
            """.formatted(AK, SK);

    private static String rfc3986(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    @Test
    void popSignatureMatchesIndependentImplementation() throws Exception {
        TestKit.RecordingSender sender = new TestKit.RecordingSender()
                .respond("{\"Code\":\"OK\",\"BizId\":\"BIZ-9\",\"Message\":\"OK\"}");
        SmsChannel ch = HioasSms.classpathChannel("channels/aliyun.api.json", INSTANCE,
                sender, new TestKit.FixedRuntime(FIXED_TS, FIXED_UUID));

        SmsResult r = ch.sendMessage("13800138000", "SMS_100", Map.of("code", "1234"));
        assertTrue(r.isSuccess());
        assertEquals("BIZ-9", r.getSmsId());

        // ---- 独立重算 POP 签名 ----
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(FIXED_TS));
        String templateParam = Json.toJson(Map.of("code", "1234"));

        Map<String, String> all = new LinkedHashMap<>();
        // 公共参数
        all.put("SignatureMethod", "HMAC-SHA1");
        all.put("SignatureNonce", FIXED_UUID);
        all.put("AccessKeyId", AK);
        all.put("SignatureVersion", "1.0");
        all.put("Timestamp", timestamp);
        all.put("Format", "JSON");
        all.put("Action", "SendSms");
        all.put("Version", "2017-05-25");
        all.put("RegionId", "cn-hangzhou");
        // 业务参数
        all.put("PhoneNumbers", "13800138000");
        all.put("SignName", "测试签名");
        all.put("TemplateParam", templateParam);
        all.put("TemplateCode", "SMS_100");

        Map<String, String> sorted = new TreeMap<>(all);
        StringBuilder canonical = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) canonical.append('&');
            first = false;
            canonical.append(rfc3986(e.getKey())).append('=').append(rfc3986(e.getValue()));
        }
        String stringToSign = "POST&" + rfc3986("/") + "&" + rfc3986(canonical.toString());
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec((SK + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String expectedSig = Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));

        // ---- 从引擎生成的 URL 中提取 Signature 并比对 ----
        String url = sender.last().url();
        String query = url.substring(url.indexOf('?') + 1);
        String actualSig = null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if ("Signature".equals(pair.substring(0, eq))) {
                actualSig = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        assertEquals(expectedSig, actualSig, "POP 签名与独立实现不一致");

        // ---- body 仅含业务参数（form 编码）----
        String body = sender.lastBodyText();
        assertTrue(body.contains("PhoneNumbers="));
        assertTrue(body.contains("TemplateCode=SMS_100"));
        assertTrue(!body.contains("AccessKeyId="), "body 不应包含公共参数");
        assertTrue(sender.last().headers().get("Content-Type").startsWith("application/x-www-form-urlencoded"));
    }
}
