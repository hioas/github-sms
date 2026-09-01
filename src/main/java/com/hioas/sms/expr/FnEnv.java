package com.hioas.sms.expr;

import com.hioas.sms.core.HioasSmsException;

import java.util.List;
import java.util.Set;

/**
 * 函数调用环境：运行时来源 + 模板渲染器 + 函数注册表。
 */
public final class FnEnv {

    /** v1.0 内置函数清单（标准 §5.6），加载期用于引用校验。 */
    public static final Set<String> NAMES = Set.of(
            "timestampSec", "timestampMs", "uuid", "nonce", "random", "utcDate", "dateOf",
            "md5", "sha1", "sha256", "hmacSha1", "hmacSha256",
            "base64", "hex", "urlEncode", "urlEncodeRfc3986", "urlDecode",
            "upper", "lower", "trim",
            "join", "prefix", "suffix", "ensurePrefix", "toJson", "values", "kvJoin", "merge",
            "sortedQueryString", "size", "str", "mapJoin", "if", "isBlank", "contains");

    private final RuntimeSource runtime;
    private TemplateRenderer renderer;

    public FnEnv(RuntimeSource runtime) {
        this.runtime = runtime;
    }

    public void attachRenderer(TemplateRenderer renderer) {
        this.renderer = renderer;
    }

    public RuntimeSource runtime() {
        return runtime;
    }

    public TemplateRenderer renderer() {
        if (renderer == null) {
            throw new HioasSmsException("模板渲染器未初始化");
        }
        return renderer;
    }

    public Object call(String fn, List<Expr> argExprs, Evaluator evaluator) {
        if (!NAMES.contains(fn)) {
            throw new HioasSmsException("未知函数: " + fn + "（内置函数见标准 §5.6）");
        }
        List<Object> args = argExprs.stream().map(evaluator::eval).toList();
        return Functions.invoke(fn, args, this, evaluator.context());
    }
}
