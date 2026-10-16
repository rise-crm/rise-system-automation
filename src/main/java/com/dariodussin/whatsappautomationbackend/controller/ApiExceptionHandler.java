package com.dariodussin.whatsappautomationbackend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> unreadableBody(HttpMessageNotReadableException ex) {
        System.err.printf("[HTTP] Bad request body | error=%s%n", rootCauseName(ex));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    private static String rootCauseName(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }
}
