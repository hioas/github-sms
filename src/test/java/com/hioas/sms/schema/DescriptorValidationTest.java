package com.hioas.sms.schema;

import com.hioas.sms.core.HioasSmsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 描述文件加载与语义校验测试。
 */
class DescriptorValidationTest {

    private static final String VALID = """
            {
              "schema": "hioas-api/1.0",
              "channel": "demo",
              "protocol": "http",
              "config": { "fields": {
                "requestUrl": { "type": "string", "required": true },
                "accessKeyId": { "type": "string", "required": true }
              }},
              "operations": { "send": {
                "request": { "method": "POST", "url": "${config.requestUrl}/send",
                  "body": { "contentType": "json", "template": { "id": "${config.accessKeyId}", "to": "${join(phones, ',')}" } } },
                "response": { "contentType": "json", "successWhen": "$.code == 0", "smsId": "$.id" }
              }}
            }
            """;

    private ChannelDescriptor load(String json) {
        ChannelDescriptor d = DescriptorLoader.descriptor(json);
        DescriptorValidator.validate(d);
        return d;
    }

    @Test
    void validDescriptorLoads() {
        ChannelDescriptor d = assertDoesNotThrow(() -> load(VALID));
        assertNotNull(d.operations.get("send"));
    }

    @Test
    void unknownFieldRejected() {
        String bad = VALID.replace("\"protocol\"", "\"protocolX\"");
        assertThrows(HioasSmsException.class, () -> load(bad));
    }

    @Test
    void undeclaredConfigFieldRejected() {
        String bad = VALID.replace("${config.accessKeyId}", "${config.notDeclared}");
        HioasSmsException e = assertThrows(HioasSmsException.class, () -> load(bad));
        assertTrue(e.getMessage().contains("notDeclared"));
    }

    @Test
    void unknownFunctionRejected() {
        String bad = VALID.replace("${join(phones, ',')}", "${nosuchfn(phones)}");
        assertThrows(HioasSmsException.class, () -> load(bad));
    }

    @Test
    void sensitiveDefaultRejected() {
        String bad = """
                { "schema": "hioas-api/1.0", "channel": "x", "protocol": "http",
                  "config": { "fields": { "secret": { "type": "string", "sensitive": true, "default": "leak" } } },
                  "operations": { "send": {
                    "request": { "method": "GET", "url": "http://x" },
                    "response": { "successWhen": "$.ok == true" } } } }
                """;
        HioasSmsException e = assertThrows(HioasSmsException.class, () -> load(bad));
        assertTrue(e.getMessage().contains("敏感"));
    }

    @Test
    void routingTargetMustExist() {
        String bad = """
                { "schema": "hioas-api/1.0", "channel": "x", "protocol": "http",
                  "operations": { "send": {
                    "request": { "method": "GET", "url": "http://x" },
                    "response": { "successWhen": "$.ok == true" } } },
                  "routing": [ { "to": "missing" } ] }
                """;
        HioasSmsException e = assertThrows(HioasSmsException.class, () -> load(bad));
        assertTrue(e.getMessage().contains("missing"));
    }

    @Test
    void undefinedDeriveRejected() {
        String bad = VALID.replace("${config.accessKeyId}", "${derive.nope}");
        assertThrows(HioasSmsException.class, () -> load(bad));
    }

    @Test
    void protocolJavaRequiresClass() {
        String bad = """
                { "schema": "hioas-api/1.0", "channel": "jd", "protocol": "java",
                  "operations": { "send": {
                    "request": { "method": "GET", "url": "http://x" },
                    "response": { "successWhen": "$.ok == true" } } } }
                """;
        HioasSmsException e = assertThrows(HioasSmsException.class, () -> load(bad));
        assertTrue(e.getMessage().contains("java.class"));
    }

    @Test
    void allBundledDescriptorsAreValid() throws Exception {
        java.net.URL dirUrl = DescriptorValidationTest.class.getClassLoader().getResource("channels");
        org.junit.jupiter.api.Assertions.assertNotNull(dirUrl, "channels 目录缺失");
        try (var stream = java.nio.file.Files.list(java.nio.file.Path.of(dirUrl.toURI()))) {
            stream.filter(p -> p.toString().endsWith(".api.json")).forEach(p -> {
                ChannelDescriptor d = DescriptorLoader.descriptorFromClasspath("channels/" + p.getFileName());
                assertDoesNotThrow(() -> DescriptorValidator.validate(d), d.channel + " 应通过校验");
            });
        }
    }
}
