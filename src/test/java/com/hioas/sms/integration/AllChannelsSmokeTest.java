package com.hioas.sms.integration;

import com.hioas.sms.HioasSms;
import com.hioas.sms.TestKit;
import com.hioas.sms.core.SmsChannel;
import com.hioas.sms.core.SmsResult;
import com.hioas.sms.expr.Json;
import com.hioas.sms.schema.ChannelDescriptor;
import com.hioas.sms.schema.DescriptorLoader;
import com.hioas.sms.schema.FieldSpec;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全渠道冒烟测试：每个内置描述文件 + 自动生成的实例配置，
 * 走通「加载 → 校验 → 路由 → 渲染 → 发送」全链路（响应用假数据，不校验业务成功）。
 */
class AllChannelsSmokeTest {

    private static final long FIXED_TS = 1700000000L;

    @TestFactory
    Stream<DynamicTest> everyChannelRendersAndSends() throws Exception {
        var dirUrl = AllChannelsSmokeTest.class.getClassLoader().getResource("channels");
        assertNotNull(dirUrl);
        List<Path> descriptors;
        try (Stream<Path> s = Files.list(Path.of(dirUrl.toURI()))) {
            descriptors = s.filter(p -> p.toString().endsWith(".api.json")).sorted().toList();
        }
        return descriptors.stream().map(p -> DynamicTest.dynamicTest(
                "smoke:" + p.getFileName(), () -> smoke(Path.of("channels").resolve(p.getFileName()).toString())));
    }

    private void smoke(String resource) {
        ChannelDescriptor d = DescriptorLoader.descriptorFromClasspath(resource);
        if ("java".equals(d.protocol)) {
            return; // SPI 渠道不在冒烟范围
        }
        String instanceJson = Json.toJson(buildInstance(d));
        TestKit.RecordingSender sender = new TestKit.RecordingSender().respond("{}");
        SmsChannel ch = HioasSms.classpathChannel(resource, instanceJson,
                sender, new TestKit.FixedRuntime(FIXED_TS, "smoke-uuid"));

        SmsResult r = ch.sendMessage("13800138000", "这是一条冒烟测试内容");
        assertNotNull(r);
        assertTrue(sender.exchanges.size() >= 1,
                d.channel + " 应产生至少一次 HTTP 调用，实际: " + sender.exchanges.size());
    }

    /** 按描述文件声明自动生成一份可校验通过的实例配置。 */
    private static Map<String, Object> buildInstance(ChannelDescriptor d) {
        Map<String, Object> config = new LinkedHashMap<>();
        for (Map.Entry<String, FieldSpec> e : d.fields().entrySet()) {
            FieldSpec f = e.getValue();
            if (f.defaultValue != null) {
                continue; // 保留描述文件默认值（如 timeZone）
            }
            Object value = switch (f.type) {
                case "number" -> 123;
                case "boolean" -> true;
                default -> f.patternEndsWith != null && f.patternEndsWith.endsWith("/")
                        ? "https://example.com/"
                        : "test-value";
            };
            config.put(e.getKey(), value);
        }
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("schema", "hioas-instance/1.0");
        instance.put("channel", d.channel);
        instance.put("configId", "smoke-" + d.channel);
        instance.put("config", config);
        return instance;
    }
}
