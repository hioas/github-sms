package com.hioas.sms.schema;

/**
 * 发送路由规则（标准 §10）。
 */
public class RoutingRule {

    /** 布尔表达式；缺省表示兜底规则 */
    public String when;
    /** 目标操作名 */
    public String to;
}
