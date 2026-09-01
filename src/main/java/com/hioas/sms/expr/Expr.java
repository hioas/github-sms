package com.hioas.sms.expr;

import java.util.List;

/**
 * 表达式 AST（标准 §5.4 语法）。
 */
public sealed interface Expr {

    record Num(Number value) implements Expr {
    }

    record Str(String value) implements Expr {
    }

    record Bool(boolean value) implements Expr {
    }

    record Null() implements Expr {
    }

    /** 顶层变量引用：config / phones / derive / $ / item 等 */
    record Var(String name) implements Expr {
    }

    /** 字段访问 a.b */
    record Path(Expr target, String field) implements Expr {
    }

    /** 数组下标 a[0] */
    record Index(Expr target, int index) implements Expr {
    }

    record Call(String fn, List<Expr> args) implements Expr {
    }

    record Binary(Expr left, String op, Expr right) implements Expr {
    }

    record Not(Expr expr) implements Expr {
    }
}
