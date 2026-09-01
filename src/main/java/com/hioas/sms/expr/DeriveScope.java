package com.hioas.sms.expr;

import com.hioas.sms.core.HioasSmsException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 派生值作用域：按需求值 + 记忆化 + 循环检测（标准 §5.5、§7.2）。
 */
public final class DeriveScope {

    private final Map<String, Object> defs;
    private final TemplateRenderer renderer;
    private final EvalContext ctx;
    private final Map<String, Object> memo = new HashMap<>();
    private final Set<String> inProgress = new HashSet<>();

    public DeriveScope(Map<String, Object> defs, TemplateRenderer renderer, EvalContext ctx) {
        this.defs = defs == null ? Map.of() : defs;
        this.renderer = renderer;
        this.ctx = ctx;
    }

    public Object get(String name) {
        if (memo.containsKey(name)) {
            return memo.get(name);
        }
        if (!defs.containsKey(name)) {
            throw new HioasSmsException("派生值未定义: derive." + name);
        }
        if (!inProgress.add(name)) {
            throw new HioasSmsException("派生值循环依赖: derive." + name);
        }
        try {
            Object value = renderer.render(defs.get(name), ctx);
            memo.put(name, value);
            return value;
        } finally {
            inProgress.remove(name);
        }
    }

    public boolean isDefined(String name) {
        return defs.containsKey(name);
    }
}
