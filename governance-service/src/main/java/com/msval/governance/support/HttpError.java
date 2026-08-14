package com.msval.governance.support;

import java.util.List;

/**
 * Part V error taxonomy carrier — one exception type the API layer maps to
 * {@code {category, detail, errors?}} JSON at the right HTTP status.
 */
public class HttpError extends RuntimeException {

    private final int status;
    private final String category;
    private final List<String> errors;

    public HttpError(int status, String category, String detail, List<String> errors) {
        super(detail);
        this.status = status;
        this.category = category;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public int status() {
        return status;
    }

    public String category() {
        return category;
    }

    public List<String> errors() {
        return errors;
    }

    public static HttpError notFound(String detail) {
        return new HttpError(404, "NOT_FOUND", detail, null);
    }

    public static HttpError conflict(String detail) {
        return new HttpError(409, "CONFLICT", detail, null);
    }

    public static HttpError unprocessable(String detail, List<String> errors) {
        return new HttpError(422, "INPUT_INVALID", detail, errors);
    }

    public static HttpError auth(String detail) {
        return new HttpError(401, "AUTH", detail, null);
    }
}
