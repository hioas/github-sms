package com.hioas.sms.expr;

/**
 * 顶层变量解析：config / phones / derive / pre / request / item / $ 等。
 */
public interface EvalContext {

    /** 解析顶层变量；未定义返回 null。 */
    Object resolve(String name);
}
