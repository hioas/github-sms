package com.hioas.sms.expr;

import com.hioas.sms.core.HioasSmsException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 内置函数库 v1.0（标准 §5.6）。函数清单封闭，新增函数 = 标准升版。
 */
final class Functions {

    private Functions() {
    }

    static Object invoke(String name, List<Object> args, FnEnv env, EvalContext ctx) {
        switch (name) {
            // ---------- 时间与随机 ----------
            case "timestampSec":
                requireArgs(name, args, 0);
                return env.runtime().epochSecond();
            case "timestampMs":
                requireArgs(name, args, 0);
                return env.runtime().epochMilli();
            case "uuid":
                requireArgs(name, args, 0);
                return env.runtime().uuid();
            case "nonce":
                requireArgs(name, args, 1);
                return env.runtime().randomAlnum(intArg(name, args, 0));
            case "random":
                requireArgs(name, args, 1);
                return env.runtime().randomDigits(intArg(name, args, 0));
            case "utcDate":
                requireArgs(name, args, 1);
                return formatDate(env.runtime().epochSecond(), strArg(name, args, 0), "UTC");
            case "dateOf":
                if (args.size() < 2 || args.size() > 3) {
                    throw new HioasSmsException("函数 dateOf 需要 2~3 个参数");
                }
                long sec = longArg(name, args, 0);
                String tz = args.size() == 3 ? strArg(name, args, 2) : "UTC";
                return formatDate(sec, strArg(name, args, 1), tz);

            // ---------- 哈希 / HMAC ----------
            case "md5":
                return digest("MD5", oneArg(name, args));
            case "sha1":
                return digest("SHA-1", oneArg(name, args));
            case "sha256":
                return digest("SHA-256", oneArg(name, args));
            case "hmacSha1":
                return hmac("HmacSHA1", args);
            case "hmacSha256":
                return hmac("HmacSHA256", args);

            // ---------- 编码 ----------
            case "base64":
                return Base64.getEncoder().encodeToString(Values.toBytes(oneArg(name, args), name));
            case "hex": {
                Object v = oneArg(name, args);
                if (!(v instanceof byte[] b)) {
                    throw new HioasSmsException("hex() 只接受字节（hmac 结果），实际: " + Values.typeName(v));
                }
                return HexFormat.of().formatHex(b);
            }
            case "urlEncode":
                return URLEncoder.encode(textArg(name, args, 0), StandardCharsets.UTF_8);
            case "urlEncodeRfc3986":
                return URLEncoder.encode(textArg(name, args, 0), StandardCharsets.UTF_8)
                        .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
            case "urlDecode":
                return URLDecoder.decode(textArg(name, args, 0), StandardCharsets.UTF_8);
            case "upper":
                return textArg(name, args, 0).toUpperCase();
            case "lower":
                return textArg(name, args, 0).toLowerCase();
            case "trim":
                return textArg(name, args, 0).trim();

            // ---------- 字符串 / 集合 ----------
            case "join": {
                requireArgs(name, args, 2);
                List<?> list = listArg(name, args, 0);
                String sep = strArg(name, args, 1);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(sep);
                    }
                    sb.append(Values.toText(list.get(i)));
                }
                return sb.toString();
            }
            case "prefix": {
                requireArgs(name, args, 2);
                List<?> list = listArg(name, args, 0);
                String p = strArg(name, args, 1);
                List<Object> out = new ArrayList<>(list.size());
                for (Object e : list) {
                    out.add(p + Values.toText(e));
                }
                return out;
            }
            case "suffix": {
                requireArgs(name, args, 2);
                List<?> list = listArg(name, args, 0);
                String s = strArg(name, args, 1);
                List<Object> out = new ArrayList<>(list.size());
                for (Object e : list) {
                    out.add(Values.toText(e) + s);
                }
                return out;
            }
            case "ensurePrefix": {
                requireArgs(name, args, 2);
                List<?> list = listArg(name, args, 0);
                String p = strArg(name, args, 1);
                List<Object> out = new ArrayList<>(list.size());
                for (Object e : list) {
                    String s = Values.toText(e);
                    out.add(s.startsWith(p) ? s : p + s);
                }
                return out;
            }
            case "toJson":
                return Json.toJson(oneArg(name, args));
            case "values": {
                requireArgs(name, args, 1);
                return new ArrayList<>(mapArg(name, args, 0).values());
            }
            case "kvJoin": {
                requireArgs(name, args, 3);
                Map<?, ?> map = mapArg(name, args, 0);
                String itemTpl = strArg(name, args, 1);
                String sep = strArg(name, args, 2);
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) {
                        sb.append(sep);
                    }
                    first = false;
                    EvalContext bound = bind(
                            bind(ctx, "key", e.getKey() == null ? "" : Values.toText(e.getKey())),
                            "value", e.getValue());
                    sb.append(Values.toText(env.renderer().render(itemTpl, bound)));
                }
                return sb.toString();
            }
            case "merge": {
                requireArgs(name, args, 2);
                Map<?, ?> a = mapArg(name, args, 0);
                Map<?, ?> b = mapArg(name, args, 1);
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : a.entrySet()) {
                    out.put(Values.toText(e.getKey()), e.getValue());
                }
                for (Map.Entry<?, ?> e : b.entrySet()) {
                    out.put(Values.toText(e.getKey()), e.getValue());
                }
                return out;
            }
            case "sortedQueryString": {
                requireArgs(name, args, 2);
                Map<?, ?> map = mapArg(name, args, 0);
                String enc = strArg(name, args, 1);
                if (!(enc.equals("none") || enc.equals("url") || enc.equals("rfc3986"))) {
                    throw new HioasSmsException("sortedQueryString 编码模式必须是 none|url|rfc3986: " + enc);
                }
                Map<String, Object> sorted = new TreeMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    sorted.put(Values.toText(e.getKey()), e.getValue());
                }
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (Map.Entry<String, Object> e : sorted.entrySet()) {
                    if (!first) {
                        sb.append('&');
                    }
                    first = false;
                    String v = e.getValue() == null ? "" : Values.toText(e.getValue());
                    sb.append(encodePart(e.getKey(), enc)).append('=').append(encodePart(v, enc));
                }
                return sb.toString();
            }
            case "size": {
                Object v = oneArg(name, args);
                if (v == null) {
                    return 0L;
                }
                if (v instanceof String s) {
                    return (long) s.length();
                }
                if (v instanceof List<?> l) {
                    return (long) l.size();
                }
                if (v instanceof Map<?, ?> m) {
                    return (long) m.size();
                }
                throw new HioasSmsException("size() 不支持类型: " + Values.typeName(v));
            }
            case "str": {
                Object v = oneArg(name, args);
                if (v == null) {
                    return null;
                }
                if (v instanceof String s) {
                    return s;
                }
                if (v instanceof Number n) {
                    return Values.numberText(n);
                }
                if (v instanceof byte[]) {
                    throw new HioasSmsException("str() 不接受字节，请先用 hex() 或 base64()");
                }
                return Values.toText(v);
            }
            case "mapJoin": {
                requireArgs(name, args, 3);
                List<?> list = listArg(name, args, 0);
                String itemTpl = strArg(name, args, 1);
                String sep = strArg(name, args, 2);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(sep);
                    }
                    EvalContext bound = bind(ctx, "item", list.get(i));
                    Object rendered = env.renderer().render(itemTpl, bound);
                    sb.append(Values.toText(rendered));
                }
                return sb.toString();
            }
            case "if": {
                requireArgs(name, args, 3);
                return Values.truth(args.get(0), "if 条件") ? args.get(1) : args.get(2);
            }
            case "isBlank": {
                Object v = oneArg(name, args);
                return v == null || (v instanceof String s && s.trim().isEmpty());
            }
            case "contains": {
                requireArgs(name, args, 2);
                return textArg(name, args, 0).contains(textArg(name, args, 1));
            }
            default:
                throw new HioasSmsException("未知函数: " + name);
        }
    }

    // ---------- 内部工具 ----------

    private static String formatDate(long sec, String pattern, String tz) {
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.of(tz));
            return fmt.format(Instant.ofEpochSecond(sec));
        } catch (Exception e) {
            throw new HioasSmsException("时间格式化失败: pattern=" + pattern + ", tz=" + tz + ": " + e.getMessage());
        }
    }

    private static String digest(String algo, Object input) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            return HexFormat.of().formatHex(md.digest(Values.toBytes(input, algo)));
        } catch (Exception e) {
            throw new HioasSmsException("摘要计算失败(" + algo + "): " + e.getMessage(), e);
        }
    }

    private static byte[] hmac(String algo, List<Object> args) {
        requireArgs(algo, args, 2);
        try {
            Mac mac = Mac.getInstance(algo);
            mac.init(new SecretKeySpec(Values.toBytes(args.get(0), algo), algo));
            return mac.doFinal(Values.toBytes(args.get(1), algo));
        } catch (HioasSmsException e) {
            throw e;
        } catch (Exception e) {
            throw new HioasSmsException("HMAC 计算失败(" + algo + "): " + e.getMessage(), e);
        }
    }

    private static String encodePart(String v, String enc) {
        return switch (enc) {
            case "url" -> URLEncoder.encode(v, StandardCharsets.UTF_8);
            case "rfc3986" -> URLEncoder.encode(v, StandardCharsets.UTF_8)
                    .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
            default -> v;
        };
    }

    /** 以覆盖式绑定构造子上下文（不修改父上下文），并透传函数环境。 */
    static EvalContext bind(EvalContext parent, String name, Object value) {
        return new BoundContext(parent, name, value);
    }

    private static final class BoundContext implements EvalContext, TemplateRenderer.FnEnvAware {
        private final EvalContext parent;
        private final String name;
        private final Object value;

        private BoundContext(EvalContext parent, String name, Object value) {
            this.parent = parent;
            this.name = name;
            this.value = value;
        }

        @Override
        public Object resolve(String n) {
            return name.equals(n) ? value : parent.resolve(n);
        }

        @Override
        public FnEnv fnEnv() {
            if (parent instanceof TemplateRenderer.FnEnvAware aware) {
                return aware.fnEnv();
            }
            throw new HioasSmsException("父上下文未绑定函数环境");
        }
    }

    private static void requireArgs(String fn, List<Object> args, int n) {
        if (args.size() != n) {
            throw new HioasSmsException("函数 " + fn + " 需要 " + n + " 个参数，实际 " + args.size());
        }
    }

    private static Object oneArg(String fn, List<Object> args) {
        requireArgs(fn, args, 1);
        return args.get(0);
    }

    private static String strArg(String fn, List<Object> args, int i) {
        Object v = args.get(i);
        if (v == null) {
            throw new HioasSmsException("函数 " + fn + " 第 " + (i + 1) + " 个参数不能为 null");
        }
        if (v instanceof byte[]) {
            throw new HioasSmsException("函数 " + fn + " 第 " + (i + 1) + " 个参数是字节，请先转换");
        }
        return Values.toText(v);
    }

    private static String textArg(String fn, List<Object> args, int i) {
        return strArg(fn, args, i);
    }

    private static int intArg(String fn, List<Object> args, int i) {
        Double d = Values.tryNumber(args.get(i));
        if (d == null) {
            throw new HioasSmsException("函数 " + fn + " 第 " + (i + 1) + " 个参数必须是数字");
        }
        return d.intValue();
    }

    private static long longArg(String fn, List<Object> args, int i) {
        Double d = Values.tryNumber(args.get(i));
        if (d == null) {
            throw new HioasSmsException("函数 " + fn + " 第 " + (i + 1) + " 个参数必须是数字");
        }
        return d.longValue();
    }

    private static List<?> listArg(String fn, List<Object> args, int i) {
        Object v = args.get(i);
        if (v instanceof List<?> l) {
            return l;
        }
        throw new HioasSmsException("函数 " + fn + " 第 " + (i + 1) + " 个参数必须是数组，实际: " + Values.typeName(v));
    }

    private static Map<?, ?> mapArg(String fn, List<Object> args, int i) {
        Object v = args.get(i);
        if (v instanceof Map<?, ?> m) {
            return m;
        }
        throw new HioasSmsException("函数 " + fn + " 第 " + (i + 1) + " 个参数必须是对象，实际: " + Values.typeName(v));
    }
}
