package com.hioas.sms.core;

import com.hioas.sms.expr.EvalScope;
import com.hioas.sms.expr.FnEnv;
import com.hioas.sms.expr.RuntimeSource;
import com.hioas.sms.expr.TemplateRenderer;
import com.hioas.sms.http.HttpSender;
import com.hioas.sms.schema.BehaviorSpec;
import com.hioas.sms.schema.ChannelDescriptor;
import com.hioas.sms.schema.OperationSpec;
import com.hioas.sms.schema.RoutingRule;
import com.hioas.sms.spi.HioasSmsAdapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道实例：描述文件 + 实例配置 + 引擎组件装配后的发送门面。
 */
public final class HioasSmsChannel implements SmsChannel {

    private final ChannelDescriptor descriptor;
    private final Map<String, Object> config;
    private final BehaviorSpec behavior;
    private final String configId;
    private final ChannelExecutor executor;   // protocol=http
    private final HioasSmsAdapter adapter;    // protocol=java
    private final FnEnv fnEnv;
    private final TemplateRenderer renderer = new TemplateRenderer();

    public HioasSmsChannel(ChannelDescriptor descriptor, String configId, Map<String, Object> config,
                           BehaviorSpec behavior, HttpSender sender, RuntimeSource runtime) {
        this.descriptor = descriptor;
        this.configId = configId;
        this.config = config;
        this.behavior = behavior;
        this.fnEnv = new FnEnv(runtime);
        this.fnEnv.attachRenderer(renderer);
        if ("java".equals(descriptor.protocol)) {
            this.adapter = loadAdapter(descriptor.java.clazz);
            this.executor = null;
        } else {
            this.adapter = null;
            this.executor = new ChannelExecutor(descriptor, config, behavior, sender, fnEnv, renderer, configId);
        }
    }

    private static HioasSmsAdapter loadAdapter(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return (HioasSmsAdapter) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new HioasSmsException("SPI 适配器加载失败: " + className + ": " + e.getMessage(), e);
        }
    }

    // ---------- SmsBlend 对齐门面 ----------

    @Override
    public SmsResult sendMessage(String phone, String message) {
        Map<String, String> vars = singleVars(message);
        return send(List.of(phone), message, defaultTemplateId(), vars);
    }

    @Override
    public SmsResult sendMessage(String phone, Map<String, String> messages) {
        return send(List.of(phone), null, defaultTemplateId(), orEmpty(messages));
    }

    @Override
    public SmsResult sendMessage(String phone, String templateId, Map<String, String> messages) {
        return send(List.of(phone), null, templateId, orEmpty(messages));
    }

    @Override
    public SmsResult massTexting(List<String> phones, String message) {
        Map<String, String> vars = singleVars(message);
        return send(phones, message, defaultTemplateId(), vars);
    }

    @Override
    public SmsResult massTexting(List<String> phones, String templateId, Map<String, String> messages) {
        return send(phones, null, templateId, orEmpty(messages));
    }

    @Override
    public SmsResult execute(String operation, Map<String, Object> params) {
        OperationSpec op = descriptor.operations.get(operation);
        if (op == null) {
            throw new HioasSmsException("操作不存在: " + operation);
        }
        if (adapter != null) {
            return adapter.send(new SendParams(operation, phonesOf(params), strOf(params.get("message")),
                    strOf(params.get("templateId")), varsOf(params), config));
        }
        Map<String, Object> runtime = runtime(phonesOf(params), strOf(params.get("message")),
                strOf(params.get("templateId")), varsOf(params));
        runtime.putAll(params); // 允许额外变量覆盖/扩展
        return executor.send(op, runtime);
    }

    @Override
    public String getConfigId() {
        return configId;
    }

    @Override
    public String getChannel() {
        return descriptor.channel;
    }

    // ---------- 内部 ----------

    private SmsResult send(List<String> phones, String message, String templateId, Map<String, String> vars) {
        if (phones == null || phones.isEmpty()) {
            throw new HioasSmsException("手机号不能为空");
        }
        String tid = templateId == null || templateId.isBlank() ? defaultTemplateId() : templateId;
        String operation = route(phones, message, tid, vars);
        if (adapter != null) {
            return adapter.send(new SendParams(operation, phones, message, tid, vars, config));
        }
        return executor.send(descriptor.operations.get(operation), runtime(phones, message, tid, vars));
    }

    /** 发送路由（标准 §10）。 */
    private String route(List<String> phones, String message, String templateId, Map<String, String> vars) {
        if (descriptor.routing == null || descriptor.routing.isEmpty()) {
            if (descriptor.operations.size() != 1) {
                throw new HioasSmsException("渠道[" + descriptor.channel + "]定义了多个操作，必须配置 routing");
            }
            return descriptor.operations.keySet().iterator().next();
        }
        EvalScope scope = new EvalScope(fnEnv);
        scope.config(config);
        scope.put("phones", phones);
        scope.put("phone", phones.get(0));
        scope.put("message", message);
        scope.put("templateId", templateId);
        scope.put("vars", vars);
        for (RoutingRule rule : descriptor.routing) {
            if (rule.when == null || renderer.renderBoolean(rule.when, scope, "routing.when")) {
                return rule.to;
            }
        }
        throw new HioasSmsException("渠道[" + descriptor.channel + "]没有匹配的路由规则");
    }

    private Map<String, Object> runtime(List<String> phones, String message, String templateId,
                                        Map<String, String> vars) {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("phones", phones);
        runtime.put("phone", phones.get(0));
        runtime.put("message", message);
        runtime.put("templateId", templateId);
        runtime.put("vars", vars);
        return runtime;
    }

    /** 单变量模板映射：{config.templateName: message}（标准 §3）。 */
    private Map<String, String> singleVars(String message) {
        Map<String, String> vars = new LinkedHashMap<>();
        Object templateName = config.get("templateName");
        if (templateName instanceof String s && !s.isBlank()) {
            vars.put(s, message);
        }
        return vars;
    }

    private String defaultTemplateId() {
        Object v = config.get("templateId");
        return v == null ? null : String.valueOf(v);
    }

    private static Map<String, String> orEmpty(Map<String, String> vars) {
        return vars == null ? Map.of() : vars;
    }

    @SuppressWarnings("unchecked")
    private static List<String> phonesOf(Map<String, Object> params) {
        Object v = params == null ? null : params.get("phones");
        if (v instanceof List<?> l) {
            return (List<String>) l;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> varsOf(Map<String, Object> params) {
        Object v = params == null ? null : params.get("vars");
        if (v instanceof Map<?, ?> m) {
            return (Map<String, String>) m;
        }
        return Map.of();
    }

    private static String strOf(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
