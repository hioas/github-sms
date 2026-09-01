package com.hioas.sms.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道门面契约：方法与签名对齐 sms4j SmsBlend，返回对齐的 SmsResult。
 * 独立项目，不依赖 sms4j（实现决策 §1）。
 */
public interface SmsChannel {

    SmsResult sendMessage(String phone, String message);

    SmsResult sendMessage(String phone, Map<String, String> messages);

    SmsResult sendMessage(String phone, String templateId, Map<String, String> messages);

    SmsResult massTexting(List<String> phones, String message);

    SmsResult massTexting(List<String> phones, String templateId, Map<String, String> messages);

    /** 通用操作入口：调用非发送类操作（如余额查询），params 提供额外运行时变量。 */
    SmsResult execute(String operation, Map<String, Object> params);

    String getConfigId();

    String getChannel();
}
