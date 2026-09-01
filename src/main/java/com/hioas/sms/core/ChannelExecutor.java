package com.hioas.sms.core;

import com.hioas.sms.expr.DeriveScope;
import com.hioas.sms.expr.EvalScope;
import com.hioas.sms.expr.Evaluator;
import com.hioas.sms.expr.ExprParser;
import com.hioas.sms.expr.FnEnv;
import com.hioas.sms.expr.Json;
import com.hioas.sms.expr.RequestLazy;
import com.hioas.sms.expr.TemplateRenderer;
import com.hioas.sms.expr.Values;
import com.hioas.sms.http.HttpExchange;
import com.hioas.sms.http.HttpOptions;
import com.hioas.sms.http.HttpResult;
import com.hioas.sms.http.HttpSender;
import com.hioas.sms.schema.BehaviorSpec;
import com.hioas.sms.schema.BodySpec;
import com.hioas.sms.schema.ChannelDescriptor;
import com.hioas.sms.schema.OperationSpec;
import com.hioas.sms.schema.PreRequestSpec;
import com.hioas.sms.schema.RequestSpec;
import com.hioas.sms.schema.ResponseSpec;
import com.hioas.sms.schema.ValidateRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道执行编排器：对一个操作完成 校验 → 前置请求 → 渲染 → 发送 → 解析 → 重试。
 * 描述文件驱动，无渠道专属代码。
 */
public final class ChannelExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChannelExecutor.class);

    private final ChannelDescriptor descriptor;
    private final Map<String, Object> config;
    private final BehaviorSpec behavior;
    private final HttpSender sender;
    private final HttpOptions options;
    private final FnEnv fnEnv;
    private final TemplateRenderer renderer;
    private final String configId;

    public ChannelExecutor(ChannelDescriptor descriptor, Map<String, Object> config,
                           BehaviorSpec behavior, HttpSender sender, FnEnv fnEnv,
                           TemplateRenderer renderer, String configId) {
        this.descriptor = descriptor;
        this.config = config;
        this.behavior = behavior;
        this.sender = sender;
        this.options = HttpOptions.from(behavior);
        this.fnEnv = fnEnv;
        this.renderer = renderer;
        this.configId = configId;
    }

    /** 发送入口：处理群发扇出与重试。 */
    public SmsResult send(OperationSpec op, Map<String, Object> runtime) {
        if ("fanout".equals(op.massStrategy())) {
            @SuppressWarnings("unchecked")
            List<String> phones = (List<String>) runtime.get("phones");
            if (phones != null && phones.size() > 1) {
                java.util.List<SmsResult> parts = new java.util.ArrayList<>();
                for (String p : phones) {
                    Map<String, Object> single = new LinkedHashMap<>(runtime);
                    single.put("phones", List.of(p));
                    single.put("phone", p);
                    parts.add(sendSingle(op, single));
                }
                return SmsResult.fanout(parts, configId);
            }
        }
        return sendSingle(op, runtime);
    }

    private SmsResult sendSingle(OperationSpec op, Map<String, Object> runtime) {
        validate(op, runtime);
        int attempts = 1 + Math.max(0, behavior.maxRetries == null ? 0 : behavior.maxRetries);
        SmsResult last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                last = attemptOnce(op, runtime);
            } catch (Exception e) {
                log.warn("渠道[{}]发送异常: {}", descriptor.channel, e.getMessage());
                last = SmsResult.fail(null, e.getMessage(), configId);
            }
            if (last.isSuccess()) {
                return last;
            }
            if (i < attempts - 1) {
                long interval = behavior.retryIntervalMs == null ? 2000 : behavior.retryIntervalMs;
                log.warn("渠道[{}]第 {} 次重试", descriptor.channel, i + 1);
                sleep(interval);
            }
        }
        return last;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 校验 ----------

    private void validate(OperationSpec op, Map<String, Object> runtime) {
        if (op.validate == null || op.validate.isEmpty()) {
            return;
        }
        EvalScope scope = runtimeScope(runtime);
        for (ValidateRule rule : op.validate) {
            boolean ok = renderer.renderBoolean(rule.check, scope, "validate[" + rule.message + "]");
            if (!ok) {
                throw new HioasSmsException(rule.message);
            }
        }
    }

    private EvalScope runtimeScope(Map<String, Object> runtime) {
        EvalScope scope = new EvalScope(fnEnv);
        scope.config(config);
        runtime.forEach(scope::put);
        return scope;
    }

    // ---------- 单次尝试 ----------

    private SmsResult attemptOnce(OperationSpec op, Map<String, Object> runtime) throws Exception {
        EvalScope scope = runtimeScope(runtime);

        // 前置请求
        for (Map.Entry<String, PreRequestSpec> e : descriptor.preRequests.entrySet()) {
            Map<String, Object> captured = runPreRequest(e.getValue(), scope);
            scope.pre(e.getKey(), captured);
        }

        RequestSpec req = op.request;
        // 惰性请求体（供签名引用 request.body）与派生值
        RequestLazy requestLazy = new RequestLazy(() ->
                req.body == null ? null : serializeBody(req.body, scope));
        scope.requestLazy(requestLazy);
        scope.derive(new DeriveScope(op.derive, renderer, scope));

        String url = renderer.renderText(req.url, scope);
        url = appendQuery(url, req.query, scope);

        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> h : req.headers.entrySet()) {
            headers.put(h.getKey(), renderer.renderText(h.getValue(), scope));
        }

        byte[] bodyBytes = null;
        if (req.body != null) {
            String body = (String) requestLazy.get("body");
            if (body != null) {
                bodyBytes = body.getBytes(charsetOf(req.body));
            }
            headers.putIfAbsent("Content-Type", defaultContentType(req.body.contentType));
        }

        HttpExchange exchange = new HttpExchange(req.method, url, headers, bodyBytes);
        log.debug("渠道[{}]请求: {} {}", descriptor.channel, req.method, url);
        HttpResult hr = sender.send(exchange, options);
        log.debug("渠道[{}]响应: {} {}", descriptor.channel, hr.status(), abbreviate(hr.body()));
        return parseResponse(op.response, hr);
    }

    private String defaultContentType(String contentType) {
        return switch (contentType) {
            case "json" -> "application/json;charset=utf-8";
            case "form" -> "application/x-www-form-urlencoded;charset=utf-8";
            default -> "text/plain;charset=utf-8";
        };
    }

    // ---------- 前置请求 ----------

    private Map<String, Object> runPreRequest(PreRequestSpec pre, EvalScope scope) throws Exception {
        RequestSpec req = pre.request;
        String url = renderer.renderText(req.url, scope);
        url = appendQuery(url, req.query, scope);
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> h : req.headers.entrySet()) {
            headers.put(h.getKey(), renderer.renderText(h.getValue(), scope));
        }
        byte[] bodyBytes = null;
        if (req.body != null) {
            String body = serializeBody(req.body, scope);
            bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            headers.putIfAbsent("Content-Type", defaultContentType(req.body.contentType));
        }
        HttpResult hr = sender.send(new HttpExchange(req.method, url, headers, bodyBytes), options);
        Object root;
        try {
            root = Json.parse(hr.body());
        } catch (Exception e) {
            throw new HioasSmsException("前置请求响应不是合法 JSON: " + abbreviate(hr.body()));
        }
        EvalScope respScope = new EvalScope(fnEnv).response(root, hr.body(), hr.status());
        Map<String, Object> captured = new LinkedHashMap<>();
        for (Map.Entry<String, String> c : pre.response.capture.entrySet()) {
            captured.put(c.getKey(), evalPath(c.getValue(), respScope));
        }
        return captured;
    }

    // ---------- 请求体序列化 ----------

    private String serializeBody(BodySpec body, EvalScope scope) {
        java.nio.charset.Charset cs = charsetOf(body);
        Object rendered = renderer.render(body.template, scope);
        String serialized = switch (body.contentType) {
            case "json" -> rendered instanceof String s ? s : Json.toJson(rendered);
            case "form" -> toForm(rendered, cs);
            case "raw" -> Values.toText(rendered);
            default -> throw new HioasSmsException("未知 body.contentType: " + body.contentType);
        };
        return switch (body.encoding) {
            case "base64" -> Base64.getEncoder().encodeToString(serialized.getBytes(cs));
            case "urlEncode" -> URLEncoder.encode(serialized, cs);
            default -> serialized;
        };
    }

    private static java.nio.charset.Charset charsetOf(BodySpec body) {
        if (body.charset == null || body.charset.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return java.nio.charset.Charset.forName(body.charset);
        } catch (Exception e) {
            throw new HioasSmsException("未知字符集: " + body.charset);
        }
    }

    private String toForm(Object rendered, java.nio.charset.Charset cs) {
        if (!(rendered instanceof Map<?, ?> map)) {
            throw new HioasSmsException("form 请求体必须渲染为对象，实际: " + Values.typeName(rendered));
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            String v = e.getValue() == null ? "" : Values.toText(e.getValue());
            sb.append(URLEncoder.encode(Values.toText(e.getKey()), cs))
                    .append('=')
                    .append(URLEncoder.encode(v, cs));
        }
        return sb.toString();
    }

    private String appendQuery(String url, Map<String, Object> query, EvalScope scope) {
        if (query == null || query.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url);
        for (Map.Entry<String, Object> e : query.entrySet()) {
            Object v = renderer.render(e.getValue(), scope);
            if (v == null) {
                continue;
            }
            if (v instanceof List<?> list) {
                for (Object item : list) {
                    appendQueryPair(sb, e.getKey(), Values.toText(item));
                }
            } else {
                appendQueryPair(sb, e.getKey(), Values.toText(v));
            }
        }
        return sb.toString();
    }

    private void appendQueryPair(StringBuilder sb, String key, String value) {
        sb.append(sb.indexOf("?") >= 0 ? '&' : '?')
                .append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    // ---------- 响应解析 ----------

    private SmsResult parseResponse(ResponseSpec spec, HttpResult hr) {
        Object root = null;
        Object data = hr.body();
        if ("json".equals(spec.contentType)) {
            try {
                root = Json.parse(hr.body());
                data = root;
            } catch (Exception e) {
                return SmsResult.fail(hr.body(), "响应不是合法 JSON: " + abbreviate(hr.body()), configId);
            }
        }
        EvalScope respScope = new EvalScope(fnEnv).response(root, hr.body(), hr.status());
        boolean success = renderer.renderBoolean(spec.successWhen, respScope, "successWhen");
        String smsId = spec.smsId == null ? null : Values.toText(evalPath(spec.smsId, respScope));
        String message = spec.message == null ? null : Values.toText(evalPath(spec.message, respScope));
        if (success) {
            return SmsResult.ok(data, smsId, configId);
        }
        return SmsResult.fail(data, message == null ? "发送失败" : message, configId);
    }

    private Object evalPath(String path, EvalScope respScope) {
        try {
            return new Evaluator(respScope, fnEnv).eval(ExprParser.parse(path));
        } catch (Exception e) {
            log.debug("响应路径[{}]提取失败: {}", path, e.getMessage());
            return null;
        }
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 512 ? s.substring(0, 512) + "..." : s;
    }
}
