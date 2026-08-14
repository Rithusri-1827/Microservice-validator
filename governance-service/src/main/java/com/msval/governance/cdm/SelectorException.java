package com.msval.governance.cdm;

/**
 * F2 — ENGINE_FAULT-class selector problem (BAD_SELECTOR / FANOUT). Publish-time bug.
 * Mirrors msval.core.cdm.paths.SelectorError.
 */
public class SelectorException extends RuntimeException {

    private final String code;

    public SelectorException(String code, String detail) {
        super(code + ": " + detail);
        this.code = code;
    }

    /** "BAD_SELECTOR" or "FANOUT". */
    public String code() {
        return code;
    }
}
