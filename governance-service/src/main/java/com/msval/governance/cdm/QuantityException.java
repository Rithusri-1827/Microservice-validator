package com.msval.governance.cdm;

/** F3 — invalid quantity string or dimension mismatch. Mirrors msval.core.cdm.quantity.QuantityError. */
public class QuantityException extends RuntimeException {

    public QuantityException(String message) {
        super(message);
    }
}
