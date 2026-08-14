package com.msval.governance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Governance service entry point (ADR-008).
 * Packages (Stage 2 layering, enforced by ArchUnit in TASK-005):
 * engine, orchestrate, gateway, registry, findings, api, persist.
 */
@SpringBootApplication
public class GovernanceApplication {
    public static void main(String[] args) {
        SpringApplication.run(GovernanceApplication.class, args);
    }
}
