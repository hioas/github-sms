package com.hioas.sms.core;

import java.util.List;
import java.util.Map;

/**
 * 规范化后的运行时发送参数（标准 §3 运行时输入契约）。
 */
public record SendParams(
        String operation,
        List<String> phones,
        String message,
        String templateId,
        Map<String, String> vars,
        Map<String, Object> config) {

    public String phone() {
        return phones.isEmpty() ? null : phones.get(0);
    }
}
