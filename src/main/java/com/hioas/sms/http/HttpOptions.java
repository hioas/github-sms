package com.hioas.sms.http;

import com.hioas.sms.schema.BehaviorSpec;

/**
 * HTTP 传输选项：超时与代理（标准 §11）。
 */
public record HttpOptions(int timeoutMs, String proxyHost, Integer proxyPort) {

    public static final int DEFAULT_TIMEOUT_MS = 10_000;

    public static HttpOptions from(BehaviorSpec behavior) {
        int timeout = behavior != null && behavior.timeoutMs != null ? behavior.timeoutMs : DEFAULT_TIMEOUT_MS;
        BehaviorSpec.ProxySpec proxy = behavior == null ? null : behavior.proxy;
        if (proxy != null && proxy.isEnabled()) {
            return new HttpOptions(timeout, proxy.host, proxy.port);
        }
        return new HttpOptions(timeout, null, null);
    }
}
