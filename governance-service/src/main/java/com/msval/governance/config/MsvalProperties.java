package com.msval.governance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * F12 — the single config surface, bound from env via application.yml
 * ({@code msval.*}). Defaults live in application.yml, not here.
 */
@ConfigurationProperties(prefix = "msval")
public record MsvalProperties(
        int queueBound,
        int dedupWindowS,
        int alertBatchMs,
        int settleDelayS,
        int reportIntervalS,
        int sweepIntervalS,
        int staleFactor,
        int gatewayPort,
        int alertPort,
        String apiToken) {
}
