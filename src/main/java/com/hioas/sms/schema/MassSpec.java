package com.hioas.sms.schema;

/**
 * 群发策略（标准 §7.4）：join 整体提交（默认）| fanout 逐号码扇出。
 */
public class MassSpec {

    public String strategy = "join";
}
