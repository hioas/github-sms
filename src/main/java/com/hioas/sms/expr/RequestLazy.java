package com.hioas.sms.expr;

import com.hioas.sms.core.HioasSmsException;

import java.util.function.Supplier;

/**
 * request.body 惰性值：序列化后的请求体字符串，供签名引用（标准 §5.2）。
 */
public final class RequestLazy {

    private final Supplier<String> bodySupplier;
    private String memo;
    private boolean inProgress;

    public RequestLazy(Supplier<String> bodySupplier) {
        this.bodySupplier = bodySupplier;
    }

    public Object get(String field) {
        if (!"body".equals(field)) {
            throw new HioasSmsException("request 仅支持 request.body，收到: request." + field);
        }
        if (memo != null) {
            return memo;
        }
        if (inProgress) {
            throw new HioasSmsException("循环依赖: 请求体序列化依赖了 request.body 自身");
        }
        inProgress = true;
        try {
            memo = bodySupplier.get();
            return memo;
        } finally {
            inProgress = false;
        }
    }
}
