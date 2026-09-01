package com.hioas.sms.expr;

import com.hioas.sms.core.HioasSmsException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表达式语言与模板渲染单元测试。
 */
class ExprEngineTest {

    private final RuntimeSource rt = RuntimeSource.system();
    private final FnEnv fnEnv = new FnEnv(rt);
    private final TemplateRenderer renderer = new TemplateRenderer();

    {
        fnEnv.attachRenderer(renderer);
    }

    private Object eval(String expr, EvalScope scope) {
        return new Evaluator(scope, fnEnv).eval(ExprParser.parse(expr));
    }

    private EvalScope emptyScope() {
        return new EvalScope(fnEnv);
    }

    @Test
    void literalsAndArithmetic() {
        EvalScope s = emptyScope();
        assertEquals(3L, eval("1 + 2", s));
        assertEquals(2.5, eval("1 + 1.5", s));
        assertEquals("ab", eval("'a' + 'b'", s));
        assertEquals("x1", eval("'x' + 1", s));
        assertEquals(true, eval("true", s));
        assertNull(eval("null", s));
    }

    @Test
    void comparisonAndLogic() {
        EvalScope s = emptyScope();
        assertEquals(true, eval("2 <= 200", s));
        assertEquals(true, eval("'0' == 0", s)); // 数字与数字字符串相等
        assertEquals(false, eval("'a' == 'b'", s));
        assertEquals(true, eval("1 < 2 && 2 < 3", s));
        assertEquals(true, eval("1 > 2 || 2 < 3", s));
        assertEquals(false, eval("!(1 == 1)", s));
    }

    @Test
    void pathAndIndex() {
        EvalScope s = emptyScope();
        s.put("config", Map.of("a", "v"));
        s.put("resp", Map.of("list", List.of(10L, 20L)));
        assertEquals("v", eval("config.a", s));
        assertEquals(20L, eval("resp.list[1]", s));
    }

    @Test
    void stringInterpolationSingleExprKeepsType() {
        EvalScope s = emptyScope();
        s.put("config", Map.of("n", 5L));
        Object single = renderer.interpolate("${config.n}", s);
        assertEquals(5L, single); // 整体单表达式保留原生类型
        Object mixed = renderer.interpolate("val=${config.n}!", s);
        assertEquals("val=5!", mixed);
    }

    @Test
    void nullOmissionAndAtEach() {
        EvalScope s = emptyScope();
        s.put("phones", List.of("138", "139"));
        s.put("vars", Map.of("code", "666"));
        Map<String, Object> tpl = Map.of(
                "records", Map.of(
                        "@each", "${phones}",
                        "@as", Map.of("mobile", "${item}", "tp", "${vars}")));
        Object out = renderer.render(tpl, s);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) out;
        @SuppressWarnings("unchecked")
        List<Object> records = (List<Object>) map.get("records");
        assertEquals(2, records.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) records.get(0);
        assertEquals("138", first.get("mobile"));
    }

    @Test
    void deriveMemoizationAndCycle() {
        EvalScope s = emptyScope();
        // 派生值只计算一次（计数器验证）
        final int[] calls = {0};
        RuntimeSource counting = new RuntimeSource() {
            @Override
            public long epochSecond() {
                calls[0]++;
                return 1000 + calls[0];
            }

            @Override
            public long epochMilli() {
                return epochSecond() * 1000;
            }

            @Override
            public String uuid() {
                return "u";
            }

            @Override
            public String randomDigits(int n) {
                return "1";
            }

            @Override
            public String randomAlnum(int n) {
                return "a";
            }
        };
        FnEnv env = new FnEnv(counting);
        env.attachRenderer(renderer);
        EvalScope scope = new EvalScope(env);
        DeriveScope derive = new DeriveScope(Map.of("t", "${timestampSec()}"), renderer, scope);
        scope.derive(derive);
        Object a = eval("derive.t", scope);
        Object b = eval("derive.t", scope);
        assertEquals(a, b);
        assertEquals(1, calls[0]); // 只算一次

        // 循环依赖
        EvalScope c = new EvalScope(env);
        DeriveScope cd = new DeriveScope(Map.of(
                "x", "${derive.y}",
                "y", "${derive.x}"), renderer, c);
        c.derive(cd);
        assertThrows(HioasSmsException.class, () -> eval("derive.x", c));
    }

    @Test
    void unknownFunctionRejected() {
        EvalScope s = emptyScope();
        assertThrows(HioasSmsException.class, () -> eval("nosuchfn()", s));
    }

    @Test
    void mapJoinAndKvJoin() {
        EvalScope s = emptyScope();
        s.put("phones", List.of("138", "139"));
        s.put("deriveMsg", "hi");
        Object joined = eval("mapJoin(phones, '${item},X', ';')", s);
        assertEquals("138,X;139,X", joined);

        java.util.LinkedHashMap<String, Object> vars = new java.util.LinkedHashMap<>();
        vars.put("a", "1");
        vars.put("b", "2");
        s.put("vars", vars);
        Object kv = eval("kvJoin(vars, '#${key}#=${value}', '&')", s);
        assertEquals("#a#=1&#b#=2", kv);
    }

    @Test
    void ifAndIsBlank() {
        EvalScope s = emptyScope();
        s.put("config", Map.of("v", ""));
        assertEquals("yes", eval("if(isBlank(config.v), 'yes', 'no')", s));
        assertEquals(true, eval("contains('abc', 'b')", s));
    }

    @Test
    void interpolateNestedBracesInLiteral() {
        // mapJoin 的模板参数内含 ${}，外层 ${} 配平不能提前闭合
        EvalScope s = emptyScope();
        s.put("phones", List.of("138"));
        s.put("msg", "M");
        Object out = renderer.interpolate("${mapJoin(phones, '${item},${msg}', ';')}", s);
        assertEquals("138,M", out);
    }

    @Test
    void expressionsInExtract() {
        List<String> exprs = TemplateRenderer.expressionsIn("a${x}b${y.z}c");
        assertEquals(List.of("x", "y.z"), exprs);
        assertTrue(TemplateRenderer.expressionsIn("plain").isEmpty());
    }
}
