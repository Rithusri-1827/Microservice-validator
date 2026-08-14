package com.msval.governance.engine;

/** F10 — an active waiver row as seen by the engine. {@code expiresAt} is an ISO timestamp string. */
public record Waiver(
        long waiverId,
        String ruleId,
        String serviceId,
        String version,
        String environment,
        String expiresAt) {
}
