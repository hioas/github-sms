package com.hioas.sms.schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实例配置（hioas-instance/1.0）：部署侧凭证与行为覆盖。
 */
public class InstanceConfig {

    public static final String SCHEMA_VERSION = "hioas-instance/1.0";

    public String schema;
    public String channel;
    public String configId;
    public Map<String, Object> config = new LinkedHashMap<>();
    public BehaviorSpec behavior;
}
