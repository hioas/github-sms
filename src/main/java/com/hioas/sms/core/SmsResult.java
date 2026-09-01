package com.hioas.sms.core;

import java.util.List;

/**
 * 发送结果，对齐 sms4j SmsResponse（success/data/configId），并扩展回执ID与错误信息。
 */
public class SmsResult {

    private final boolean success;
    private final Object data;
    private final String smsId;
    private final String message;
    private final String configId;

    private SmsResult(boolean success, Object data, String smsId, String message, String configId) {
        this.success = success;
        this.data = data;
        this.smsId = smsId;
        this.message = message;
        this.configId = configId;
    }

    public static SmsResult ok(Object data, String smsId, String configId) {
        return new SmsResult(true, data, smsId, null, configId);
    }

    public static SmsResult fail(Object data, String message, String configId) {
        return new SmsResult(false, data, null, message, configId);
    }

    public static SmsResult fanout(List<SmsResult> parts, String configId) {
        boolean all = parts.stream().allMatch(SmsResult::isSuccess);
        List<Object> data = parts.stream().map(SmsResult::getData).toList();
        if (all) {
            return new SmsResult(true, data, null, null, configId);
        }
        String msg = parts.stream()
                .filter(r -> !r.isSuccess())
                .map(r -> r.getMessage() == null ? "发送失败" : r.getMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("发送失败");
        return new SmsResult(false, data, null, msg, configId);
    }

    public boolean isSuccess() {
        return success;
    }

    public Object getData() {
        return data;
    }

    public String getSmsId() {
        return smsId;
    }

    public String getMessage() {
        return message;
    }

    public String getConfigId() {
        return configId;
    }

    @Override
    public String toString() {
        return "SmsResult{success=" + success + ", smsId=" + smsId + ", message=" + message
                + ", configId=" + configId + ", data=" + data + '}';
    }
}
