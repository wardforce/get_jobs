package com.getjobs.worker.zhilian;

public class ZhilianAuthenticationExpiredException extends RuntimeException {
    public ZhilianAuthenticationExpiredException(String message) {
        super(message);
    }
}
