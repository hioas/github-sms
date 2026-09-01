package com.hioas.sms.expr;

import com.hioas.sms.core.HioasSmsException;

import java.util.ArrayList;
import java.util.List;

/**
 * 表达式解析器：词法分析 + 递归下降语法分析。
 *
 * 语法（优先级从低到高）：
 *   or       := and ('||' and)*
 *   and      := equality ('&&' equality)*
 *   equality := comparison (('==' | '!=') comparison)*
 *   comparison := addition (('<' | '<=' | '>' | '>=') addition)*
 *   addition := unary ('+' unary)*
 *   unary    := '!' unary | postfix
 *   postfix  := primary (('.' IDENT) | ('[' INT ']'))*
 *   primary  := NUM | STR | true | false | null | '$'
 *             | IDENT '(' args ')' | IDENT | '(' expr ')'
 */
public final class ExprParser {

    private final String src;
    private int pos;

    private ExprParser(String src) {
        this.src = src;
    }

    public static Expr parse(String expression) {
        ExprParser p = new ExprParser(expression);
        Expr e = p.parseOr();
        p.skipWs();
        if (p.pos < p.src.length()) {
            throw fail("表达式解析失败，多余的内容: '" + p.src.substring(p.pos) + "'", p.src, p.pos);
        }
        return e;
    }

    // ---------- 语法 ----------

    private Expr parseOr() {
        Expr e = parseAnd();
        while (match("||")) {
            e = new Expr.Binary(e, "||", parseAnd());
        }
        return e;
    }

    private Expr parseAnd() {
        Expr e = parseEquality();
        while (match("&&")) {
            e = new Expr.Binary(e, "&&", parseEquality());
        }
        return e;
    }

    private Expr parseEquality() {
        Expr e = parseComparison();
        while (true) {
            if (match("==")) {
                e = new Expr.Binary(e, "==", parseComparison());
            } else if (match("!=")) {
                e = new Expr.Binary(e, "!=", parseComparison());
            } else {
                return e;
            }
        }
    }

    private Expr parseComparison() {
        Expr e = parseAddition();
        while (true) {
            if (match("<=")) {
                e = new Expr.Binary(e, "<=", parseAddition());
            } else if (match(">=")) {
                e = new Expr.Binary(e, ">=", parseAddition());
            } else if (match("<")) {
                e = new Expr.Binary(e, "<", parseAddition());
            } else if (match(">")) {
                e = new Expr.Binary(e, ">", parseAddition());
            } else {
                return e;
            }
        }
    }

    private Expr parseAddition() {
        Expr e = parseUnary();
        while (peekChar() == '+') {
            pos++;
            e = new Expr.Binary(e, "+", parseUnary());
        }
        return e;
    }

    private Expr parseUnary() {
        skipWs();
        if (peekChar() == '!') {
            pos++;
            return new Expr.Not(parseUnary());
        }
        return parsePostfix();
    }

    private Expr parsePostfix() {
        Expr e = parsePrimary();
        while (true) {
            skipWs();
            char c = peekChar();
            if (c == '.') {
                pos++;
                String field = readIdent();
                e = new Expr.Path(e, field);
            } else if (c == '[') {
                pos++;
                skipWs();
                int start = pos;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
                if (start == pos) {
                    throw fail("下标必须是数字", src, start);
                }
                int idx = Integer.parseInt(src.substring(start, pos));
                skipWs();
                expect(']');
                e = new Expr.Index(e, idx);
            } else {
                return e;
            }
        }
    }

    private Expr parsePrimary() {
        skipWs();
        if (pos >= src.length()) {
            throw fail("表达式意外结束", src, pos);
        }
        char c = src.charAt(pos);
        if (c == '(') {
            pos++;
            Expr e = parseOr();
            skipWs();
            expect(')');
            return e;
        }
        if (c == '$') {
            pos++;
            return new Expr.Var("$");
        }
        if (c == '\'' || c == '"') {
            return new Expr.Str(readString(c));
        }
        if (Character.isDigit(c)) {
            return readNumber();
        }
        if (isIdentStart(c)) {
            String name = readIdent();
            skipWs();
            if (peekChar() == '(') {
                pos++;
                List<Expr> args = new ArrayList<>();
                skipWs();
                if (peekChar() != ')') {
                    args.add(parseOr());
                    while (true) {
                        skipWs();
                        if (peekChar() == ',') {
                            pos++;
                            args.add(parseOr());
                        } else {
                            break;
                        }
                    }
                }
                skipWs();
                expect(')');
                return new Expr.Call(name, args);
            }
            return switch (name) {
                case "true" -> new Expr.Bool(true);
                case "false" -> new Expr.Bool(false);
                case "null" -> new Expr.Null();
                default -> new Expr.Var(name);
            };
        }
        throw fail("无法识别的字符: '" + c + "'", src, pos);
    }

    // ---------- 词法 ----------

    private Expr.Num readNumber() {
        int start = pos;
        boolean isDouble = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isDigit(c)) {
                pos++;
            } else if (c == '.' && !isDouble) {
                isDouble = true;
                pos++;
            } else {
                break;
            }
        }
        String text = src.substring(start, pos);
        if (isDouble) {
            return new Expr.Num(Double.parseDouble(text));
        }
        return new Expr.Num(Long.parseLong(text));
    }

    private String readString(char quote) {
        pos++; // 跳过起始引号
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\\' && pos + 1 < src.length()) {
                char next = src.charAt(pos + 1);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    default -> throw fail("非法转义: \\" + next, src, pos);
                }
                pos += 2;
                continue;
            }
            if (c == quote) {
                pos++;
                return sb.toString();
            }
            sb.append(c);
            pos++;
        }
        throw fail("字符串未闭合", src, pos);
    }

    private String readIdent() {
        skipWs();
        int start = pos;
        if (pos >= src.length() || !isIdentStart(src.charAt(pos))) {
            throw fail("期望标识符", src, pos);
        }
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        return src.substring(start, pos);
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private boolean match(String op) {
        skipWs();
        if (src.startsWith(op, pos)) {
            pos += op.length();
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (peekChar() != c) {
            throw fail("期望字符 '" + c + "'", src, pos);
        }
        pos++;
    }

    private char peekChar() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private static HioasSmsException fail(String reason, String src, int at) {
        return new HioasSmsException("表达式错误[" + src + "]在位置" + at + ": " + reason);
    }
}
