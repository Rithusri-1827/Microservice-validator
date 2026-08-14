package com.msval.governance.cdm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One resolved (concrete_path, value) pair produced by {@link PathResolver#resolve}.
 * {@code value} is {@link PathResolver#MISSING} when the path does not exist.
 */
public record Slice(String path, JsonNode value) {
}
