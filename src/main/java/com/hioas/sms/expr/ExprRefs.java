package com.hioas.sms.expr;

import java.util.HashSet;
import java.util.Set;

/**
 * 表达式引用收集：加载期校验用（标准 §4 快速失败）。
 */
public final class ExprRefs {

    public final Set<String> rootVars = new HashSet<>();
    public final Set<String> configFields = new HashSet<>();
    public final Set<String> deriveNames = new HashSet<>();
    public final Set<String> preNames = new HashSet<>();
    public final Set<String> functions = new HashSet<>();

    public void collect(Expr e) {
        if (e instanceof Expr.Var v) {
            rootVars.add(v.name());
        } else if (e instanceof Expr.Path p) {
            if (p.target() instanceof Expr.Var v) {
                rootVars.add(v.name());
                if ("config".equals(v.name())) {
                    configFields.add(p.field());
                } else if ("derive".equals(v.name())) {
                    deriveNames.add(p.field());
                } else if ("pre".equals(v.name())) {
                    preNames.add(p.field());
                }
                return;
            }
            collect(p.target());
        } else if (e instanceof Expr.Index ix) {
            collect(ix.target());
        } else if (e instanceof Expr.Call c) {
            functions.add(c.fn());
            c.args().forEach(this::collect);
        } else if (e instanceof Expr.Binary b) {
            collect(b.left());
            collect(b.right());
        } else if (e instanceof Expr.Not n) {
            collect(n.expr());
        }
    }
}
