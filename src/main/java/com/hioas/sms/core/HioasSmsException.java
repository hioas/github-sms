package com.hioas.sms.core;

public class HioasSmsException extends RuntimeException {

    public HioasSmsException(String message) {
        super(message);
    }

    public HioasSmsException(String message, Throwable cause) {
        super(message, cause);
    }
}
