package com.hioas.sms.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道描述文件模型（hioas-api/1.0）。
 */
public class ChannelDescriptor {

    public static final String SCHEMA_VERSION = "hioas-api/1.0";

    public String schema;
    public String channel;
    public String title;
    public String description;
    /** http | java */
    public String protocol;
    public JavaSpec java;
    public ConfigSpec config;
    public Map<String, PreRequestSpec> preRequests = new LinkedHashMap<>();
    public Map<String, OperationSpec> operations = new LinkedHashMap<>();
    public List<RoutingRule> routing = new ArrayList<>();
    public BehaviorSpec behavior;

    public Map<String, FieldSpec> fields() {
        return config == null ? Map.of() : config.fields;
    }
}
