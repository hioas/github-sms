package com.hioas.sms.expr;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 求值作用域：配置 / 运行时变量 / 前置请求结果 / 惰性派生值 / 循环变量 / 响应视图。
 * 实现 EvalContext 供求值器解析顶层变量，实现 FnEnvAware 供模板渲染取函数环境。
 */
public final class EvalScope implements EvalContext, TemplateRenderer.FnEnvAware {

    private final Map<String, Object> roots = new LinkedHashMap<>();
    private final Deque<Map<String, Object>> loops = new ArrayDeque<>();
    private final FnEnv fnEnv;
    private DeriveScope derive;
    private RequestLazy requestLazy;

    /** 响应视图（成功判定阶段） */
    private Object respRoot;
    private String respText;
    private Integer respStatus;
    private boolean respMode;

    public EvalScope(FnEnv fnEnv) {
        this.fnEnv = fnEnv;
    }

    // ---------- 装配 ----------

    public EvalScope put(String name, Object value) {
        roots.put(name, value);
        return this;
    }

    public EvalScope config(Map<String, Object> config) {
        roots.put("config", config);
        return this;
    }

    public EvalScope derive(DeriveScope scope) {
        this.derive = scope;
        return this;
    }

    public EvalScope requestLazy(RequestLazy lazy) {
        this.requestLazy = lazy;
        return this;
    }

    public EvalScope pre(String name, Object value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> pre = (Map<String, Object>) roots.computeIfAbsent("pre", k -> new LinkedHashMap<>());
        pre.put(name, value);
        return this;
    }

    public EvalScope response(Object root, String text, Integer status) {
        this.respRoot = root;
        this.respText = text;
        this.respStatus = status;
        this.respMode = true;
        return this;
    }

    // ---------- 循环变量 ----------

    public void pushLoop(String name, Object value) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put(name, value);
        loops.push(frame);
    }

    public void popLoop() {
        loops.pop();
    }

    // ---------- EvalContext ----------

    @Override
    public Object resolve(String name) {
        for (Map<String, Object> frame : loops) {
            if (frame.containsKey(name)) {
                return frame.get(name);
            }
        }
        return switch (name) {
            case "derive" -> derive;
            case "request" -> requestLazy;
            case "$" -> respRoot;
            case "resp" -> respMode ? respView() : roots.get(name);
            default -> roots.get(name);
        };
    }

    private Map<String, Object> respView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("text", respText);
        view.put("status", respStatus);
        return view;
    }

    // ---------- FnEnvAware ----------

    @Override
    public FnEnv fnEnv() {
        return fnEnv;
    }
}
