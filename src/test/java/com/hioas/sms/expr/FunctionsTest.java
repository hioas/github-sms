package com.hioas.sms.expr;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 内置函数库测试：密码学函数用已知向量校验。
 */
class FunctionsTest {

    private final FnEnv fnEnv = new FnEnv(RuntimeSource.system());
    private final TemplateRenderer renderer = new TemplateRenderer();

    {
        fnEnv.attachRenderer(renderer);
    }

    private Object eval(String expr) {
        return new Evaluator(new EvalScope(fnEnv), fnEnv).eval(ExprParser.parse(expr));
    }

    @Test
    void md5KnownVector() {
        // md5("abc") = 900150983cd24fb0d6963f7d28e17f72
        assertEquals("900150983cd24fb0d6963f7d28e17f72", eval("md5('abc')"));
    }

    @Test
    void sha256KnownVector() {
        // sha256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                eval("sha256('abc')"));
    }

    @Test
    void hmacSha256KnownVector() {
        // RFC 4231 test case 2: key="Jefe", data="what do ya want for nothing?"
        // expected hex = 5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843
        Object out = eval("hex(hmacSha256('Jefe', 'what do ya want for nothing?'))");
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", out);
    }

    @Test
    void base64AndHex() {
        assertEquals("YWJj", eval("base64('abc')"));
        assertEquals("abc", new String(java.util.Base64.getDecoder().decode("YWJj")));
    }

    @Test
    void urlEncodeVariants() {
        assertEquals("a%20b", eval("urlEncodeRfc3986('a b')"));
        assertEquals("a+b", eval("urlEncode('a b')"));
        assertEquals("%2A", eval("urlEncodeRfc3986('*')"));
        assertEquals("~", eval("urlEncodeRfc3986('~')"));
    }

    @Test
    void sortedQueryString() {
        EvalScope s = new EvalScope(fnEnv);
        s.put("m", new java.util.LinkedHashMap<>(Map.of("b", "2", "a", "1 2")));
        Object out = new Evaluator(s, fnEnv).eval(ExprParser.parse("sortedQueryString(m, 'rfc3986')"));
        assertEquals("a=1%202&b=2", out);
    }

    @Test
    void joinPrefixSuffixMergeValues() {
        EvalScope s = new EvalScope(fnEnv);
        s.put("phones", List.of("138", "139"));
        Evaluator ev = new Evaluator(s, fnEnv);
        assertEquals("138,139", ev.eval(ExprParser.parse("join(phones, ',')")));
        assertEquals(List.of("+86138", "+86139"), ev.eval(ExprParser.parse("prefix(phones, '+86')")));
        s.put("a", Map.of("k1", "v1"));
        s.put("b", Map.of("k2", "v2"));
        Object merged = ev.eval(ExprParser.parse("merge(a, b)"));
        assertEquals(Map.of("k1", "v1", "k2", "v2"), merged);
        s.put("vars", new java.util.LinkedHashMap<>(Map.of("x", "1")));
        assertEquals(List.of("1"), ev.eval(ExprParser.parse("values(vars)")));
    }

    @Test
    void dateFunctionsDeterministic() {
        FnEnv env = new FnEnv(new TestRuntime(1704067200L)); // 2024-01-01T00:00:00Z
        env.attachRenderer(renderer);
        Evaluator ev = new Evaluator(new EvalScope(env), env);
        assertEquals("2024-01-01T00:00:00Z", ev.eval(ExprParser.parse("utcDate(\"yyyy-MM-dd'T'HH:mm:ss'Z'\")")));
        assertEquals("2024-01-01", ev.eval(ExprParser.parse("dateOf(timestampSec(), 'yyyy-MM-dd', 'UTC')")));
    }

    @Test
    void sizeAndStr() {
        assertEquals(3L, eval("size('abc')"));
        assertEquals("5", eval("str(5)"));
    }

    private static final class TestRuntime implements RuntimeSource {
        private final long sec;

        TestRuntime(long sec) {
            this.sec = sec;
        }

        @Override
        public long epochSecond() {
            return sec;
        }

        @Override
        public long epochMilli() {
            return sec * 1000;
        }

        @Override
        public String uuid() {
            return "test-uuid";
        }

        @Override
        public String randomDigits(int n) {
            return "1".repeat(n);
        }

        @Override
        public String randomAlnum(int n) {
            return "a".repeat(n);
        }
    }
}
