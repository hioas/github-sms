package com.hioas.sms.expr;

import com.hioas.sms.core.HioasSmsException;

import java.util.List;
import java.util.Map;

/**
 * AST 求值器。
 */
public final class Evaluator {

    private final EvalContext ctx;
    private final FnEnv fnEnv;

    public Evaluator(EvalContext ctx, FnEnv fnEnv) {
        this.ctx = ctx;
        this.fnEnv = fnEnv;
    }

    public EvalContext context() {
        return ctx;
    }

    public Object eval(Expr expr) {
        if (expr instanceof Expr.Num n) {
            return n.value();
        }
        if (expr instanceof Expr.Str s) {
            return s.value();
        }
        if (expr instanceof Expr.Bool b) {
            return b.value();
        }
        if (expr instanceof Expr.Null) {
            return null;
        }
        if (expr instanceof Expr.Not not) {
            return !Values.truth(eval(not.expr()), "逻辑非(!)");
        }
        if (expr instanceof Expr.Var v) {
            return ctx.resolve(v.name());
        }
        if (expr instanceof Expr.Path p) {
            return member(eval(p.target()), p.field());
        }
        if (expr instanceof Expr.Index ix) {
            return index(eval(ix.target()), ix.index());
        }
        if (expr instanceof Expr.Call call) {
            return fnEnv.call(call.fn(), call.args(), this);
        }
        if (expr instanceof Expr.Binary bin) {
            return binary(bin);
        }
        throw new HioasSmsException("未知表达式节点: " + expr);
    }

    private Object binary(Expr.Binary bin) {
        String op = bin.op();
        if (op.equals("&&")) {
            boolean l = Values.truth(eval(bin.left()), "&&");
            return l && Values.truth(eval(bin.right()), "&&");
        }
        if (op.equals("||")) {
            boolean l = Values.truth(eval(bin.left()), "||");
            return l || Values.truth(eval(bin.right()), "||");
        }
        Object l = eval(bin.left());
        Object r = eval(bin.right());
        return switch (op) {
            case "+" -> Values.plus(l, r, "拼接(+)");
            case "==" -> Values.equals(l, r);
            case "!=" -> !Values.equals(l, r);
            case "<" -> Values.compare(l, r, "<") < 0;
            case "<=" -> Values.compare(l, r, "<=") <= 0;
            case ">" -> Values.compare(l, r, ">") > 0;
            case ">=" -> Values.compare(l, r, ">=") >= 0;
            default -> throw new HioasSmsException("未知运算符: " + op);
        };
    }

    /** 字段访问：支持派生值作用域、惰性请求体、Map。 */
    public static Object member(Object target, String field) {
        if (target == null) {
            throw new HioasSmsException("不能在 null 上访问字段 ." + field);
        }
        if (target instanceof DeriveScope d) {
            return d.get(field);
        }
        if (target instanceof RequestLazy r) {
            return r.get(field);
        }
        if (target instanceof Map<?, ?> m) {
            return m.get(field);
        }
        throw new HioasSmsException("不能在 " + Values.typeName(target) + " 上访问字段 ." + field);
    }

    public static Object index(Object target, int index) {
        if (target == null) {
            throw new HioasSmsException("不能在 null 上访问下标 [" + index + "]");
        }
        if (target instanceof List<?> list) {
            if (index < 0 || index >= list.size()) {
                return null;
            }
            return list.get(index);
        }
        throw new HioasSmsException("不能在 " + Values.typeName(target) + " 上访问下标 [" + index + "]");
    }
}
