package com.orion.network.api;

import com.orion.network.core.NetworkException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns errors from the core into JSON responses: {"error": "..."}. */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(NetworkException.class)
    public ResponseEntity<Map<String, String>> network(NetworkException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> badJson(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "request body is not valid JSON for this call"));
    }
}
