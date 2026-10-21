package com.dariodussin.whatsappautomationbackend.controller;

import org.springframework.dao.DataAccessException;
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

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Void> dataStoreUnavailable(DataAccessException ex) {
        System.err.printf("[HTTP] Data store unavailable | error=%s%n", rootCauseName(ex));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    private static String rootCauseName(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }
}
