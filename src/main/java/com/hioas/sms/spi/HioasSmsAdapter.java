package com.hioas.sms.spi;

import com.hioas.sms.core.SendParams;
import com.hioas.sms.core.SmsResult;

/**
 * SPI 兜底适配器（标准 §12）：纯 SDK 渠道（如京东云）实现此接口，
 * 由描述文件 protocol=java + java.class 声明，JDK SPI/反射加载。
 */
public interface HioasSmsAdapter {

    SmsResult send(SendParams params);
}
