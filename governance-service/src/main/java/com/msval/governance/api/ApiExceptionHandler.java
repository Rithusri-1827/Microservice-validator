package com.msval.governance.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.persist.Jsonb;
import com.msval.governance.registry.RegistryService;
import com.msval.governance.support.HttpError;

/** Part V — JSON errors {category, detail, errors?} per the boundary table. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(HttpError.class)
    public ResponseEntity<ObjectNode> httpError(HttpError e) {
        ObjectNode body = Jsonb.MAPPER.createObjectNode();
        body.put("category", e.category());
        body.put("detail", e.getMessage());
        if (!e.errors().isEmpty()) {
            var arr = body.putArray("errors");
            e.errors().forEach(arr::add);
        }
        return ResponseEntity.status(e.status()).body(body);
    }

    /** IF-006: 422 {unroutable: [rule_ids], errors}. */
    @ExceptionHandler(RegistryService.UnroutableBundle.class)
    public ResponseEntity<ObjectNode> unroutable(RegistryService.UnroutableBundle e) {
        ObjectNode body = Jsonb.MAPPER.createObjectNode();
        body.put("category", e.error.category());
        body.put("detail", e.error.getMessage());
        var un = body.putArray("unroutable");
        e.unroutable.forEach(un::add);
        var arr = body.putArray("errors");
        e.error.errors().forEach(arr::add);
        return ResponseEntity.status(422).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ObjectNode> unreadable(HttpMessageNotReadableException e) {
        ObjectNode body = Jsonb.MAPPER.createObjectNode();
        body.put("category", "INPUT_INVALID");
        body.put("detail", "unreadable request body");
        return ResponseEntity.status(422).body(body);
    }

    /** DB down ⇒ 503 STORE_UNAVAILABLE (NFR-003). */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ObjectNode> storeDown(DataAccessResourceFailureException e) {
        ObjectNode body = Jsonb.MAPPER.createObjectNode();
        body.put("category", "STORE_UNAVAILABLE");
        body.put("detail", "governance store unreachable");
        return ResponseEntity.status(503).body(body);
    }

    /** Anything else escaping a controller is an engine/service fault (Part V: 500 {category}). */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ObjectNode> fault(Exception e) {
        log.error("unhandled API fault", e);
        ObjectNode body = Jsonb.MAPPER.createObjectNode();
        body.put("category", "ENGINE_FAULT");
        body.put("detail", String.valueOf(e.getMessage()));
        return ResponseEntity.status(500).body(body);
    }
}
