package com.hioas.sms;

import com.hioas.sms.core.HioasSmsChannel;
import com.hioas.sms.expr.RuntimeSource;
import com.hioas.sms.http.HttpSender;
import com.hioas.sms.http.JdkHttpSender;
import com.hioas.sms.schema.BehaviorSpec;
import com.hioas.sms.schema.ChannelDescriptor;
import com.hioas.sms.schema.DescriptorLoader;
import com.hioas.sms.schema.DescriptorValidator;
import com.hioas.sms.schema.InstanceConfig;
import com.hioas.sms.schema.InstanceResolver;

import java.util.Map;

/**
 * hioas-sms 入口：描述文件 + 实例配置 → 可发送的渠道实例。
 *
 * <pre>{@code
 * SmsChannel ch = HioasSms.channel(
 *     HioasSms.classpathDescriptorJson("channels/zhutong.api.json"),
 *     instanceJson);
 * SmsResult r = ch.sendMessage("13800138000", "1234");
 * }</pre>
 */
public final class HioasSms {

    private HioasSms() {
    }

    public static HioasSmsChannel channel(String descriptorJson, String instanceJson) {
        return channel(descriptorJson, instanceJson, new JdkHttpSender(), RuntimeSource.system());
    }

    /** 可注入 HTTP 发送器与运行时来源（测试/代理场景）。 */
    public static HioasSmsChannel channel(String descriptorJson, String instanceJson,
                                          HttpSender sender, RuntimeSource runtime) {
        ChannelDescriptor descriptor = DescriptorLoader.descriptor(descriptorJson);
        DescriptorValidator.validate(descriptor);
        InstanceConfig instance = DescriptorLoader.instance(instanceJson);
        return assemble(descriptor, instance, sender, runtime);
    }

    public static HioasSmsChannel classpathChannel(String descriptorResource, String instanceJson) {
        return classpathChannel(descriptorResource, instanceJson, new JdkHttpSender(), RuntimeSource.system());
    }

    public static HioasSmsChannel classpathChannel(String descriptorResource, String instanceJson,
                                                   HttpSender sender, RuntimeSource runtime) {
        ChannelDescriptor descriptor = DescriptorLoader.descriptorFromClasspath(descriptorResource);
        DescriptorValidator.validate(descriptor);
        InstanceConfig instance = DescriptorLoader.instance(instanceJson);
        return assemble(descriptor, instance, sender, runtime);
    }

    private static HioasSmsChannel assemble(ChannelDescriptor descriptor, InstanceConfig instance,
                                            HttpSender sender, RuntimeSource runtime) {
        Map<String, Object> config = InstanceResolver.resolve(descriptor, instance);
        BehaviorSpec behavior = BehaviorSpec.merge(descriptor.behavior, instance.behavior);
        return new HioasSmsChannel(descriptor, instance.configId, config, behavior, sender, runtime);
    }

    /** 读取 classpath 描述文件原文（便捷方法）。 */
    public static String classpathDescriptorJson(String resource) {
        try (var in = HioasSms.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new com.hioas.sms.core.HioasSmsException("classpath 中未找到: " + resource);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (com.hioas.sms.core.HioasSmsException e) {
            throw e;
        } catch (Exception e) {
            throw new com.hioas.sms.core.HioasSmsException("读取资源失败: " + resource, e);
        }
    }
}
