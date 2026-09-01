package com.hioas.sms.schema;

/**
 * 引擎行为（标准 §11）：重试 / 超时 / 代理。实例配置可覆盖描述文件默认值。
 */
public class BehaviorSpec {

    public Integer maxRetries;
    public Integer retryIntervalMs;
    public Integer timeoutMs;
    public ProxySpec proxy;

    public static class ProxySpec {
        public Boolean enable;
        public String host;
        public Integer port;

        public boolean isEnabled() {
            return Boolean.TRUE.equals(enable);
        }
    }

    /** 以 override 中非空字段覆盖 base。 */
    public static BehaviorSpec merge(BehaviorSpec base, BehaviorSpec override) {
        BehaviorSpec out = new BehaviorSpec();
        BehaviorSpec b = base == null ? new BehaviorSpec() : base;
        BehaviorSpec o = override == null ? new BehaviorSpec() : override;
        out.maxRetries = o.maxRetries != null ? o.maxRetries : (b.maxRetries != null ? b.maxRetries : 0);
        out.retryIntervalMs = o.retryIntervalMs != null ? o.retryIntervalMs
                : (b.retryIntervalMs != null ? b.retryIntervalMs : 2000);
        out.timeoutMs = o.timeoutMs != null ? o.timeoutMs : b.timeoutMs;
        out.proxy = o.proxy != null ? o.proxy : b.proxy;
        return out;
    }
}
