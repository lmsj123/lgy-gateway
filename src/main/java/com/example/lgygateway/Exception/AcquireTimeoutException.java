package com.example.lgygateway.Exception;

public class AcquireTimeoutException extends Exception {
    public AcquireTimeoutException(String rateLimitExceeded) {
        super(rateLimitExceeded);
    }
}
