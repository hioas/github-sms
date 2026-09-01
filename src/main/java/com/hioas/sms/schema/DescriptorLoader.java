package com.hioas.sms.schema;

import com.hioas.sms.core.HioasSmsException;
import com.hioas.sms.expr.Json;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 描述文件 / 实例配置加载器（Jackson 严格绑定，未知字段即报错）。
 */
public final class DescriptorLoader {

    private DescriptorLoader() {
    }

    public static ChannelDescriptor descriptor(String json) {
        try {
            return Json.MAPPER.readValue(json, ChannelDescriptor.class);
        } catch (HioasSmsException e) {
            throw e;
        } catch (Exception e) {
            throw new HioasSmsException("描述文件解析失败: " + e.getMessage(), e);
        }
    }

    public static InstanceConfig instance(String json) {
        try {
            return Json.MAPPER.readValue(json, InstanceConfig.class);
        } catch (Exception e) {
            throw new HioasSmsException("实例配置解析失败: " + e.getMessage(), e);
        }
    }

    public static ChannelDescriptor descriptorFromClasspath(String resource) {
        try (InputStream in = DescriptorLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new HioasSmsException("classpath 中未找到描述文件: " + resource);
            }
            return descriptor(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (HioasSmsException e) {
            throw e;
        } catch (Exception e) {
            throw new HioasSmsException("读取描述文件失败: " + resource + ": " + e.getMessage(), e);
        }
    }

    public static InstanceConfig instanceFromFile(Path path) {
        try {
            return instance(Files.readString(path, StandardCharsets.UTF_8));
        } catch (HioasSmsException e) {
            throw e;
        } catch (Exception e) {
            throw new HioasSmsException("读取实例配置失败: " + path + ": " + e.getMessage(), e);
        }
    }
}
