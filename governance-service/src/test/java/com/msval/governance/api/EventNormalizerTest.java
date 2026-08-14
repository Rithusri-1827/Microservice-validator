package com.msval.governance.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.persist.Jsonb;

/** DD-005 — EventNormalizer mirrors msval/core/normalize/event.py field-for-field. */
class EventNormalizerTest {

    private static JsonNode json(String s) throws IOException {
        return Jsonb.MAPPER.readTree(s);
    }

    @Test
    void deploymentPassesThroughApprovedCdm() throws IOException {
        JsonNode payload = json("""
                {"kind":"deployment","event_id":"e1","service_id":"svc","service_version":"1.0",
                 "environment":"dev","approved_cdm":{"cdm_version":"1.0",
                   "service":{"id":"svc","name":"svc","layer":"Domain"},
                   "version":{"tag":"1.0"},"environment":"dev",
                   "workload":{"replicas":1,"containers":[{"name":"app",
                     "image":{"repository":"r","registry":"harbor.internal","ref":"r:1","pinned":true}}]}}}
                """);
        EventNormalizer.Normalized n = EventNormalizer.normalize(payload);
        assertTrue(n.errors().isEmpty(), "errors: " + n.errors());
        assertEquals("svc", n.doc().path("service").path("id").asText());
    }

    @Test
    void deploymentWithoutCdmIsA3Fallback() throws IOException {
        EventNormalizer.Normalized n = EventNormalizer.normalize(json("{\"kind\":\"deployment\"}"));
        assertNull(n.doc());
        assertEquals(1, n.errors().size());
        assertTrue(n.errors().get(0).contains("A-3"));
    }

    @Test
    void statusReportWrapsLiveState() throws IOException {
        JsonNode payload = json("""
                {"kind":"status_report","event_id":"e2","service_id":"svc","service_version":"2.1",
                 "environment":"staging","trigger":"change",
                 "live_state":{"replicas":3,"containers":[{"name":"app",
                   "image":{"repository":"r","registry":"harbor.internal","ref":"r:2.1","pinned":true}}]}}
                """);
        EventNormalizer.Normalized n = EventNormalizer.normalize(payload);
        assertTrue(n.errors().isEmpty(), "errors: " + n.errors());
        JsonNode doc = n.doc();
        assertEquals("svc", doc.path("service").path("id").asText());
        assertEquals("2.1", doc.path("version").path("tag").asText());
        assertEquals("staging", doc.path("environment").asText());
        assertEquals(3, doc.path("workload").path("replicas").asInt());
        assertEquals("event-json", doc.path("provenance").path("source_format").asText());
        assertEquals("e2", doc.path("provenance").path("source_refs").get(0).asText());
    }

    @Test
    void statusReportWithoutContainersGetsPlaceholder() throws IOException {
        JsonNode payload = json("""
                {"kind":"status_report","event_id":"e3","service_id":"svc","service_version":"1",
                 "environment":"dev","trigger":"interval","live_state":{}}
                """);
        EventNormalizer.Normalized n = EventNormalizer.normalize(payload);
        assertEquals("unknown",
                n.doc().path("workload").path("containers").get(0).path("name").asText());
        assertTrue(n.errors().isEmpty());
    }

    @Test
    void unknownKindIsAnError() throws IOException {
        EventNormalizer.Normalized n = EventNormalizer.normalize(json("{\"kind\":\"mystery\"}"));
        assertNull(n.doc());
        assertTrue(n.errors().get(0).contains("unknown event kind"));
    }

    @Test
    void validateCdmFlagsMissingRequiredFields() throws IOException {
        JsonNode bad = json("{\"workload\":{\"containers\":[]}}");
        var errors = EventNormalizer.validateCdm(bad);
        assertTrue(errors.stream().anyMatch(e -> e.contains("/cdm_version")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("/service/id")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("containers")));
    }
}
