package com.hioas.sms.schema;

import com.hioas.sms.core.HioasSmsException;
import com.hioas.sms.expr.Expr;
import com.hioas.sms.expr.ExprParser;
import com.hioas.sms.expr.ExprRefs;
import com.hioas.sms.expr.FnEnv;
import com.hioas.sms.expr.TemplateRenderer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 描述文件语义校验（标准 §13 快速失败）。加载期一次性完成：
 * 结构合法性、表达式可解析、引用闭环（config/derive/pre/函数）。
 */
public final class DescriptorValidator {

    /** 请求构造阶段允许的顶层变量 */
    private static final Set<String> REQUEST_ROOTS =
            Set.of("config", "phones", "phone", "message", "templateId", "vars", "derive", "pre", "request", "item");
    /** 响应判定阶段允许的顶层变量 */
    private static final Set<String> RESPONSE_ROOTS = Set.of("$", "resp", "config");
    /** 路由/前置请求阶段允许的顶层变量 */
    private static final Set<String> ROUTING_ROOTS =
            Set.of("config", "phones", "phone", "message", "templateId", "vars", "pre");

    private final ChannelDescriptor d;

    private DescriptorValidator(ChannelDescriptor d) {
        this.d = d;
    }

    public static void validate(ChannelDescriptor d) {
        new DescriptorValidator(d).run();
    }

    private void run() {
        if (!ChannelDescriptor.SCHEMA_VERSION.equals(d.schema)) {
            throw err("schema 必须是 " + ChannelDescriptor.SCHEMA_VERSION + "，实际: " + d.schema);
        }
        if (d.channel == null || !d.channel.matches("^[a-z][a-z0-9_-]*$")) {
            throw err("channel 必须是小写字母开头的标识: " + d.channel);
        }
        if (!"http".equals(d.protocol) && !"java".equals(d.protocol)) {
            throw err("protocol 必须是 http|java，实际: " + d.protocol);
        }
        validateConfigFields();

        if ("java".equals(d.protocol)) {
            if (d.java == null || d.java.clazz == null || d.java.clazz.isBlank()) {
                throw err("protocol=java 必须声明 java.class 适配器类");
            }
            return;
        }

        if (d.operations.isEmpty()) {
            throw err("protocol=http 必须定义至少一个 operation");
        }
        for (Map.Entry<String, OperationSpec> e : d.operations.entrySet()) {
            validateOperation(e.getKey(), e.getValue());
        }
        for (RoutingRule r : d.routing) {
            if (r.to == null || !d.operations.containsKey(r.to)) {
                throw err("路由目标不存在: " + r.to);
            }
            if (r.when != null) {
                checkExpr(r.when, "routing[" + r.to + "].when", ROUTING_ROOTS, null);
            }
        }
        for (Map.Entry<String, PreRequestSpec> e : d.preRequests.entrySet()) {
            validatePreRequest(e.getKey(), e.getValue());
        }
    }

    private void validateConfigFields() {
        if (d.config == null) {
            return;
        }
        for (Map.Entry<String, FieldSpec> e : d.config.fields.entrySet()) {
            FieldSpec f = e.getValue();
            if (!Set.of("string", "number", "boolean").contains(f.type)) {
                throw err("config.fields." + e.getKey() + ".type 非法: " + f.type);
            }
            if (f.isSensitive() && f.defaultValue != null) {
                throw err("config.fields." + e.getKey() + " 为敏感字段，描述文件禁止提供默认值（标准 §13）");
            }
        }
    }

    private void validateOperation(String name, OperationSpec op) {
        String loc = "operations." + name;
        if (op.request == null) {
            throw err(loc + " 缺少 request");
        }
        if (op.response == null) {
            throw err(loc + " 缺少 response");
        }
        RequestSpec req = op.request;
        if (!"GET".equals(req.method) && !"POST".equals(req.method)) {
            throw err(loc + ".request.method 必须是 GET|POST，实际: " + req.method);
        }
        if (req.url == null || req.url.isBlank()) {
            throw err(loc + ".request.url 不能为空");
        }
        if ("GET".equals(req.method) && req.body != null) {
            throw err(loc + ": GET 请求不允许定义 body");
        }
        if (op.mass != null && !Set.of("join", "fanout").contains(op.mass.strategy)) {
            throw err(loc + ".mass.strategy 必须是 join|fanout");
        }

        Set<String> roots = opRoots(op);
        for (ValidateRule rule : op.validate) {
            if (rule.check == null || rule.message == null) {
                throw err(loc + ".validate 规则必须包含 check 与 message");
            }
            checkExpr(rule.check, loc + ".validate", roots, op);
        }

        // derive 定义本身是模板
        for (Map.Entry<String, Object> e : op.derive.entrySet()) {
            checkTemplate(e.getValue(), loc + ".derive." + e.getKey(), roots, op);
        }

        checkExprText(req.url, loc + ".request.url", roots, op);
        for (Map.Entry<String, String> h : req.headers.entrySet()) {
            checkExprText(h.getValue(), loc + ".request.headers." + h.getKey(), roots, op);
        }
        for (Map.Entry<String, Object> q : req.query.entrySet()) {
            checkTemplate(q.getValue(), loc + ".request.query." + q.getKey(), roots, op);
        }
        if (req.body != null) {
            BodySpec body = req.body;
            if (!Set.of("json", "form", "raw").contains(body.contentType)) {
                throw err(loc + ".request.body.contentType 必须是 json|form|raw");
            }
            if (!Set.of("none", "base64", "urlEncode").contains(body.encoding)) {
                throw err(loc + ".request.body.encoding 必须是 none|base64|urlEncode");
            }
            if (body.template == null) {
                throw err(loc + ".request.body.template 不能为空");
            }
            if ("raw".equals(body.contentType) && !(body.template instanceof String)) {
                throw err(loc + ": raw body 的 template 必须是字符串");
            }
            checkTemplate(body.template, loc + ".request.body.template", roots, op);
        }

        ResponseSpec resp = op.response;
        if (!Set.of("json", "text").contains(resp.contentType)) {
            throw err(loc + ".response.contentType 必须是 json|text");
        }
        if (resp.successWhen == null || resp.successWhen.isBlank()) {
            throw err(loc + ".response.successWhen 不能为空");
        }
        checkExpr(resp.successWhen, loc + ".response.successWhen", RESPONSE_ROOTS, op);
        if (resp.smsId != null) {
            checkExpr(resp.smsId, loc + ".response.smsId", RESPONSE_ROOTS, op);
        }
        if (resp.message != null) {
            checkExpr(resp.message, loc + ".response.message", RESPONSE_ROOTS, op);
        }
    }

    /** 请求阶段的顶层变量白名单 = 固定集合 + 该操作声明的 inputs。 */
    private static Set<String> opRoots(OperationSpec op) {
        if (op.inputs == null || op.inputs.isEmpty()) {
            return REQUEST_ROOTS;
        }
        Set<String> roots = new java.util.LinkedHashSet<>(REQUEST_ROOTS);
        roots.addAll(op.inputs.keySet());
        return roots;
    }

    private void validatePreRequest(String name, PreRequestSpec pre) {
        String loc = "preRequests." + name;
        if (pre.request == null || pre.response == null || pre.response.capture.isEmpty()) {
            throw err(loc + " 必须定义 request 与 response.capture");
        }
        RequestSpec req = pre.request;
        if (!"GET".equals(req.method) && !"POST".equals(req.method)) {
            throw err(loc + ".request.method 必须是 GET|POST");
        }
        if (req.url == null || req.url.isBlank()) {
            throw err(loc + ".request.url 不能为空");
        }
        checkExprText(req.url, loc + ".request.url", ROUTING_ROOTS, null);
        for (Map.Entry<String, String> h : req.headers.entrySet()) {
            checkExprText(h.getValue(), loc + ".request.headers." + h.getKey(), ROUTING_ROOTS, null);
        }
        for (Map.Entry<String, Object> q : req.query.entrySet()) {
            checkTemplate(q.getValue(), loc + ".request.query." + q.getKey(), ROUTING_ROOTS, null);
        }
        if (req.body != null) {
            checkTemplate(req.body.template, loc + ".request.body.template", ROUTING_ROOTS, null);
        }
    }

    // ---------- 表达式与引用检查 ----------

    /** 模板递归：收集其中所有 ${} 表达式并校验。 */
    private void checkTemplate(Object template, String loc, Set<String> roots, OperationSpec op) {
        if (template instanceof String s) {
            for (String expr : TemplateRenderer.expressionsIn(s)) {
                checkExpr(expr, loc, roots, op);
            }
        } else if (template instanceof Map<?, ?> m) {
            if (m.containsKey("@each")) {
                if (m.size() != 2 || !m.containsKey("@as")) {
                    throw err(loc + ": @each 必须且只能与 @as 成对出现");
                }
                checkTemplate(m.get("@each"), loc + ".@each", roots, op);
                checkTemplate(m.get("@as"), loc + ".@as", roots, op);
                return;
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                checkTemplate(e.getValue(), loc + "." + e.getKey(), roots, op);
            }
        } else if (template instanceof List<?> l) {
            int i = 0;
            for (Object e : l) {
                checkTemplate(e, loc + "[" + i++ + "]", roots, op);
            }
        }
    }

    private void checkExprText(String text, String loc, Set<String> roots, OperationSpec op) {
        if (text == null) {
            return;
        }
        for (String expr : TemplateRenderer.expressionsIn(text)) {
            checkExpr(expr, loc, roots, op);
        }
    }

    private void checkExpr(String exprText, String loc, Set<String> roots, OperationSpec op) {
        Expr expr;
        try {
            expr = ExprParser.parse(exprText);
        } catch (HioasSmsException e) {
            throw err(loc + ": " + e.getMessage());
        }
        ExprRefs refs = new ExprRefs();
        refs.collect(expr);
        for (String v : refs.rootVars) {
            if (!roots.contains(v)) {
                throw err(loc + ": 不允许引用变量 " + v + "（表达式: " + exprText + "）");
            }
        }
        for (String f : refs.configFields) {
            if (!d.fields().containsKey(f)) {
                throw err(loc + ": 未声明的配置字段 config." + f + "（表达式: " + exprText + "）");
            }
        }
        for (String dn : refs.deriveNames) {
            if (op == null || !op.derive.containsKey(dn)) {
                throw err(loc + ": 未定义的派生值 derive." + dn + "（表达式: " + exprText + "）");
            }
        }
        for (String pn : refs.preNames) {
            if (!d.preRequests.containsKey(pn)) {
                throw err(loc + ": 未定义的前置请求 pre." + pn + "（表达式: " + exprText + "）");
            }
        }
        for (String fn : refs.functions) {
            if (!FnEnv.NAMES.contains(fn)) {
                throw err(loc + ": 未知函数 " + fn + "（表达式: " + exprText + "）");
            }
        }
    }

    private HioasSmsException err(String message) {
        return new HioasSmsException("描述文件[" + d.channel + "]校验失败: " + message);
    }
}
