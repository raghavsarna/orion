package com.orion.controller.api;

import com.orion.controller.core.ControllerException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Turns errors from the core into JSON responses: {"error": "..."}. */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(ControllerException.class)
    public ResponseEntity<Map<String, String>> controller(ControllerException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> badJson(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "request body is not valid JSON for this call"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> badParam(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "bad value for " + e.getName()));
    }
}
