package com.hioas.sms.expr;

import com.hioas.sms.core.HioasSmsException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 值类型转换规则（标准 §5.3）。
 * 类型集合：String / Long / Double / Boolean / List / Map / byte[] / null。
 */
public final class Values {

    private Values() {
    }

    public static byte[] toBytes(Object v, String fnName) {
        if (v instanceof byte[] b) {
            return b;
        }
        if (v instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        throw new HioasSmsException(fnName + " 参数必须是字符串或字节，实际: " + typeName(v));
    }

    public static String toText(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Double d) {
            return numberText(d);
        }
        if (v instanceof List || v instanceof Map) {
            return Json.toJson(v);
        }
        return String.valueOf(v);
    }

    public static String numberText(Number n) {
        if (n instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf(d.longValue());
            }
            return String.valueOf(d);
        }
        return String.valueOf(n);
    }

    /** 拼接运算：字符串拼接；数字相加；字节禁止（标准 §5.3）。 */
    public static Object plus(Object a, Object b, String opDesc) {
        if (a instanceof byte[] || b instanceof byte[]) {
            throw new HioasSmsException(opDesc + ": 字节(bytes)不允许参与拼接，请先用 hex() 或 base64() 转换");
        }
        boolean aNum = a instanceof Number;
        boolean bNum = b instanceof Number;
        if (aNum && bNum) {
            if (a instanceof Double || b instanceof Double) {
                return ((Number) a).doubleValue() + ((Number) b).doubleValue();
            }
            return ((Number) a).longValue() + ((Number) b).longValue();
        }
        if (a == null || b == null) {
            throw new HioasSmsException(opDesc + ": null 不允许参与拼接，可用 if() 处理可空值");
        }
        return toText(a) + toText(b);
    }

    /** 比较：== != 返回 null 表示不可比较；其余运算符要求可排序。 */
    public static boolean equals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        if (a instanceof Number || b instanceof Number) {
            Double da = tryNumber(a);
            Double db = tryNumber(b);
            if (da != null && db != null) {
                return da.doubleValue() == db.doubleValue();
            }
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return a.equals(b);
        }
        return toText(a).equals(toText(b));
    }

    public static int compare(Object a, Object b, String op) {
        if (a == null || b == null) {
            throw new HioasSmsException("运算符 " + op + " 不支持 null 参与比较");
        }
        Double da = tryNumber(a);
        Double db = tryNumber(b);
        if (da != null && db != null) {
            return Double.compare(da, db);
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa.compareTo(sb);
        }
        throw new HioasSmsException("运算符 " + op + " 不支持的类型: " + typeName(a) + " 与 " + typeName(b));
    }

    public static boolean truth(Object v, String where) {
        if (v instanceof Boolean b) {
            return b;
        }
        throw new HioasSmsException(where + " 要求布尔值，实际: " + typeName(v));
    }

    public static Double tryNumber(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static String typeName(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof byte[]) {
            return "bytes";
        }
        if (v instanceof String) {
            return "string";
        }
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof List) {
            return "list";
        }
        if (v instanceof Map) {
            return "map";
        }
        return v.getClass().getSimpleName();
    }
}
