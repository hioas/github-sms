package com.hioas.sms.integration;

import com.hioas.sms.HioasSms;
import com.hioas.sms.TestKit;
import com.hioas.sms.core.SmsChannel;
import com.hioas.sms.core.SmsResult;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C 级渠道（腾讯云）：TC3-HMAC-SHA256 四步密钥派生。
 * 关键点：签名对「实际发送的 body 字节」做 SHA256，验证 ${request.body} 惰性机制与多级派生链。
 */
class TencentSignatureTest {

    private static final String AK = "AKIDtest";
    private static final String SK = "secretTest";
    private static final long FIXED_TS = 1551113065L;

    private static final String INSTANCE = """
            { "schema": "hioas-instance/1.0", "channel": "tencent", "configId": "tc-1",
              "config": { "requestUrl": "sms.tencentcloudapi.com",
                          "accessKeyId": "%s", "accessKeySecret": "%s",
                          "sdkAppId": "1400006666", "signature": "测试签名",
                          "templateId": "112233", "templateName": "code" } }
            """.formatted(AK, SK);

    private static byte[] hmac256(byte[] key, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void tc3SignatureMatchesIndependentImplementation() throws Exception {
        String okResp = "{\"Response\":{\"SendStatusSet\":[{\"Code\":\"Ok\",\"SerialNo\":\"SN-1\"}],\"RequestId\":\"r\"}}";
        TestKit.RecordingSender sender = new TestKit.RecordingSender().respond(okResp);
        SmsChannel ch = HioasSms.classpathChannel("channels/tencent.api.json", INSTANCE,
                sender, new TestKit.FixedRuntime(FIXED_TS, "u-1"));

        SmsResult r = ch.sendMessage("13800138000", "112233", Map.of("code", "5678"));
        assertTrue(r.isSuccess());
        assertEquals("SN-1", r.getSmsId());

        // ---- 独立重算 TC3 签名，payload 取实际发送的 body 字节 ----
        byte[] payload = sender.last().body();
        String date = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(FIXED_TS));
        String canonicalHeaders = "content-type:application/json; charset=utf-8\nhost:sms.tencentcloudapi.com\n";
        String credentialScope = date + "/sms/tc3_request";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\ncontent-type;host\n" + sha256Hex(payload);
        String stringToSign = "TC3-HMAC-SHA256\n" + FIXED_TS + "\n" + credentialScope + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] secretDate = hmac256(("TC3" + SK).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac256(secretDate, "sms");
        byte[] secretSigning = hmac256(secretService, "tc3_request");
        String expectedSig = HexFormat.of().formatHex(hmac256(secretSigning, stringToSign));

        // ---- 从 Authorization 头提取 Signature 比对 ----
        String auth = sender.last().headers().get("Authorization");
        assertTrue(auth.startsWith("TC3-HMAC-SHA256 Credential=" + AK + "/" + credentialScope),
                "Authorization 前缀不符: " + auth);
        String actualSig = auth.substring(auth.indexOf("Signature=") + "Signature=".length());
        assertEquals(expectedSig, actualSig, "TC3 签名与独立实现不一致");
        assertEquals(String.valueOf(FIXED_TS), sender.last().headers().get("X-TC-Timestamp"));
    }
}
