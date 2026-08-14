package com.msval.governance.persist;

/** The (service, version, environment) triple — primary key of {@code lifecycle_states}. */
public record ServiceKey(String serviceId, String version, String environment) {
}
