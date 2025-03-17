package com.example.lgygateway.Exception;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class AllExceptionHandler {
    @ExceptionHandler(AcquireTimeoutException.class)
    public void handler(){

    }
}
