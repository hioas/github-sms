package com.hioas.sms.expr;

import com.hioas.sms.core.HioasSmsException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板渲染器：${} 插值、结构化模板遍历、@each 展开、null 省略（标准 §5.1、§7.3~7.4）。
 */
public final class TemplateRenderer {

    /** 渲染模板：Map / List / String / 标量。 */
    public Object render(Object template, EvalContext ctx) {
        if (template instanceof Map<?, ?> map) {
            return renderMap(map, ctx);
        }
        if (template instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object e : list) {
                out.add(render(e, ctx));
            }
            return out;
        }
        if (template instanceof String s) {
            return interpolate(s, ctx);
        }
        return template;
    }

    /** 渲染为字符串（url / header 值等）。 */
    public String renderText(String template, EvalContext ctx) {
        Object v = interpolate(template, ctx);
        if (v == null) {
            return "";
        }
        if (v instanceof byte[]) {
            throw new HioasSmsException("模板结果不允许为字节，请先用 hex() 或 base64(): " + template);
        }
        return Values.toText(v);
    }

    /** 渲染为布尔值（路由/校验/成功判定）：入参为裸表达式，不是 ${} 模板。 */
    public boolean renderBoolean(String expression, String where) {
        return renderBoolean(expression, null, where);
    }

    public boolean renderBoolean(String expression, EvalContext ctx, String where) {
        Object v = new Evaluator(ctx, fnEnvOf(ctx)).eval(ExprParser.parse(expression));
        return Values.truth(v, where);
    }

    @SuppressWarnings("unchecked")
    private Object renderMap(Map<?, ?> map, EvalContext ctx) {
        // @each 循环指令
        if (map.containsKey("@each")) {
            Object each = map.get("@each");
            Object as = map.get("@as");
            if (as == null || map.size() != 2) {
                throw new HioasSmsException("@each 指令必须且只能与 @as 成对出现");
            }
            Object src = render(each, ctx);
            if (src == null) {
                throw new HioasSmsException("@each 数据源为 null");
            }
            if (!(src instanceof List<?> list)) {
                throw new HioasSmsException("@each 数据源必须是数组，实际: " + Values.typeName(src));
            }
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                EvalContext bound = Functions.bind(ctx, "item", item);
                out.add(render(as, bound));
            }
            return out;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object v = render(e.getValue(), ctx);
            if (v == null) {
                continue; // null 省略（标准 §7.3.4）
            }
            out.put(Values.toText(e.getKey()), v);
        }
        return out;
    }

    /**
     * 字符串插值。整体恰为单个 ${expr} 时保留原生类型，否则拼接为字符串。
     */
    public Object interpolate(String s, EvalContext ctx) {
        if (s == null || s.isEmpty() || s.indexOf("${") < 0) {
            return s;
        }
        List<Object> segments = scan(s);
        if (segments.size() == 1 && segments.get(0) instanceof Expr expr) {
            return new Evaluator(ctx, fnEnvOf(ctx)).eval(expr);
        }
        StringBuilder sb = new StringBuilder();
        for (Object seg : segments) {
            if (seg instanceof Expr expr) {
                Object v = new Evaluator(ctx, fnEnvOf(ctx)).eval(expr);
                if (v instanceof byte[]) {
                    throw new HioasSmsException("插值结果不允许为字节，请先用 hex() 或 base64(): " + s);
                }
                sb.append(Values.toText(v));
            } else {
                sb.append(seg);
            }
        }
        return sb.toString();
    }

    private FnEnv fnEnvOf(EvalContext ctx) {
        if (ctx instanceof FnEnvAware aware) {
            return aware.fnEnv();
        }
        throw new HioasSmsException("求值上下文未绑定函数环境");
    }

    /**
     * 提取字符串中所有 ${} 表达式的原文（加载期引用校验用）。
     */
    public static List<String> expressionsIn(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.indexOf("${") < 0) {
            return out;
        }
        for (int[] r : ranges(s)) {
            out.add(s.substring(r[0], r[1]));
        }
        return out;
    }

    /**
     * 扫描模板字符串为「字面量 / 表达式」段序列。
     * 花括号配平时跳过引号内内容（支持 mapJoin 模板参数中的 ${} 嵌套）。
     */
    private static List<Object> scan(String s) {
        List<Object> segments = new ArrayList<>();
        int i = 0;
        for (int[] r : ranges(s)) {
            int open = r[0] - 2; // "${" 的位置
            if (open > i) {
                segments.add(s.substring(i, open));
            }
            segments.add(ExprParser.parse(s.substring(r[0], r[1])));
            i = r[1] + 1; // 跳过闭合的 '}'
        }
        if (i < s.length()) {
            segments.add(s.substring(i));
        }
        return segments;
    }

    /** 返回每个 ${...} 中表达式文本的 [start, end) 区间（含花括号配平与引号跳过）。 */
    private static List<int[]> ranges(String s) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        int len = s.length();
        while (i < len) {
            int j = s.indexOf("${", i);
            if (j < 0) {
                break;
            }
            int k = j + 2;
            int depth = 1;
            char quote = 0;
            while (k < len && depth > 0) {
                char c = s.charAt(k);
                if (quote != 0) {
                    if (c == '\\' && k + 1 < len) {
                        k++;
                    } else if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '\'' || c == '"') {
                    quote = c;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
                k++;
            }
            if (depth > 0) {
                throw new HioasSmsException("模板插值未闭合: " + s);
            }
            out.add(new int[]{j + 2, k - 1});
            i = k;
        }
        return out;
    }

    /** 上下文实现此接口以提供函数环境。 */
    public interface FnEnvAware {
        FnEnv fnEnv();
    }
}
