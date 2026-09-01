package com.hioas.sms.integration;

import com.hioas.sms.HioasSms;
import com.hioas.sms.TestKit;
import com.hioas.sms.core.SmsChannel;
import com.hioas.sms.core.SmsResult;
import com.hioas.sms.expr.Json;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B 级渠道（助通）：双重 MD5 派生签名、@each 数组展开、双操作路由、发送前校验。
 */
class ZhutongSendTest {

    private static final String INSTANCE = """
            { "schema": "hioas-instance/1.0", "channel": "zhutong", "configId": "zt-1",
              "config": { "requestUrl": "https://api.mix2.zthysms.com/",
                          "accessKeyId": "user1", "accessKeySecret": "secret123",
                          "signature": "【测试】", "templateId": "TP001", "templateName": "code" } }
            """;
    private static final long FIXED_TS = 1700000000L;

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void templateSendBuildsRecordsAndDoubleMd5() {
        TestKit.RecordingSender sender = new TestKit.RecordingSender()
                .respond("{\"code\":200,\"msg\":\"ok\"}");
        SmsChannel ch = HioasSms.classpathChannel("channels/zhutong.api.json", INSTANCE,
                sender, new TestKit.FixedRuntime(FIXED_TS, "u-1"));

        SmsResult r = ch.sendMessage("13800138000", "TP001", Map.of("code", "6666"));
        assertTrue(r.isSuccess());

        Map<?, ?> body = (Map<?, ?>) Json.parse(sender.lastBodyText());
        assertEquals("user1", body.get("username"));
        assertEquals("TP001", body.get("tpId"));
        assertEquals("【测试】", body.get("signature"));

        // tKey = 固定时间戳（原生数字）
        assertEquals(FIXED_TS, ((Number) body.get("tKey")).longValue());
        // password = md5(md5(secret) + tKey)
        String expected = md5(md5("secret123") + FIXED_TS);
        assertEquals(expected, body.get("password"));

        // records 数组：逐号码展开
        List<?> records = (List<?>) body.get("records");
        assertEquals(1, records.size());
        Map<?, ?> rec = (Map<?, ?>) records.get(0);
        assertEquals("13800138000", rec.get("mobile"));
        assertEquals(Map.of("code", "6666"), rec.get("tpContent"));

        assertEquals("https://api.mix2.zthysms.com/v2/sendSmsTp", sender.last().url());
    }

    @Test
    void massTemplateSendExpandsAllPhones() {
        TestKit.RecordingSender sender = new TestKit.RecordingSender()
                .respond("{\"code\":200}");
        SmsChannel ch = HioasSms.classpathChannel("channels/zhutong.api.json", INSTANCE,
                sender, new TestKit.FixedRuntime(FIXED_TS, "u-1"));

        ch.massTexting(List.of("138", "139", "137"), "TP001", Map.of("code", "1"));
        Map<?, ?> body = (Map<?, ?>) Json.parse(sender.lastBodyText());
        List<?> records = (List<?>) body.get("records");
        assertEquals(3, records.size());
    }

    @Test
    void customSendRoutedWhenNoTemplate() {
        // 无 templateId → 走自定义短信
        String instanceNoTpl = """
                { "schema": "hioas-instance/1.0", "channel": "zhutong", "configId": "zt-2",
                  "config": { "requestUrl": "https://api.mix2.zthysms.com/",
                              "accessKeyId": "user1", "accessKeySecret": "secret123" } }
                """;
        TestKit.RecordingSender sender = new TestKit.RecordingSender().respond("{\"code\":200}");
        SmsChannel ch = HioasSms.classpathChannel("channels/zhutong.api.json", instanceNoTpl,
                sender, new TestKit.FixedRuntime(FIXED_TS, "u-1"));

        SmsResult r = ch.sendMessage("13800138000", "【测试】您的验证码是8888");
        assertTrue(r.isSuccess());
        assertEquals("https://api.mix2.zthysms.com/v2/sendSms", sender.last().url());
        Map<?, ?> body = (Map<?, ?>) Json.parse(sender.lastBodyText());
        assertEquals("13800138000", body.get("mobile"));
        assertEquals("【测试】您的验证码是8888", body.get("content"));
        // 自定义短信 tKey 为字符串
        assertEquals(String.valueOf(FIXED_TS), body.get("tKey"));
    }

    @Test
    void validateRejectsContentWithoutSignatureBracket() {
        String instanceNoTpl = """
                { "schema": "hioas-instance/1.0", "channel": "zhutong", "configId": "zt-3",
                  "config": { "requestUrl": "https://api.mix2.zthysms.com/",
                              "accessKeyId": "user1", "accessKeySecret": "secret123" } }
                """;
        TestKit.RecordingSender sender = new TestKit.RecordingSender();
        SmsChannel ch = HioasSms.classpathChannel("channels/zhutong.api.json", instanceNoTpl,
                sender, new TestKit.FixedRuntime(FIXED_TS, "u-1"));

        // 不含【 → 校验失败
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.hioas.sms.core.HioasSmsException.class,
                () -> ch.sendMessage("13800138000", "没有签名的内容"));
        assertTrue(ex.getMessage().contains("签名"));
        assertEquals(0, sender.exchanges.size());
    }
}
