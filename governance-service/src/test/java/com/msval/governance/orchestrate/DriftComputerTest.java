package com.msval.governance.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.engine.CheckResult;
import com.msval.governance.persist.Jsonb;

/** D4-4 — the declared-vs-live diff producing CONFIG_DRIFT / TOPOLOGY_DRIFT synthetic results. */
class DriftComputerTest {

    private static JsonNode json(String s) throws IOException {
        return Jsonb.MAPPER.readTree(s);
    }

    private static final String DECLARED = """
            {"cdm_version":"1.0","service":{"id":"svc"},"version":{"tag":"1"},"environment":"dev",
             "workload":{"replicas":1,"containers":[{"name":"app",
               "image":{"repository":"payments/api","registry":"harbor.internal","ref":"a:1","pinned":true},
               "security":{"run_as_non_root":true},
               "resources":{"limits":{"memory":"1Gi"}}}]}}
            """;

    @Test
    void identicalLiveStateIsClean() throws IOException {
        JsonNode declared = json(DECLARED);
        JsonNode live = json("""
                {"containers":[{"name":"app",
                  "image":{"repository":"payments/api","registry":"harbor.internal","ref":"a:1","pinned":true},
                  "security":{"run_as_non_root":true},
                  "resources":{"limits":{"memory":"1Gi"}}}]}
                """);
        assertNull(DriftComputer.configDrift(declared, live));
    }

    @Test
    void changedImageIsConfigDriftWithExactPath() throws IOException {
        JsonNode declared = json(DECLARED);
        JsonNode live = json("""
                {"containers":[{"name":"app",
                  "image":{"repository":"payments/api","registry":"harbor.internal","ref":"a:2-hotfix","pinned":true},
                  "security":{"run_as_non_root":true},
                  "resources":{"limits":{"memory":"1Gi"}}}]}
                """);
        CheckResult r = DriftComputer.configDrift(declared, live);
        assertEquals("DRIFT-CONFIG", r.ruleId());
        assertEquals("CONFIG_DRIFT", r.reasonCode());
        assertTrue(r.detail().contains("workload.containers[0].image"), r.detail());
    }

    @Test
    void keyOrderDoesNotMatter_canonicalDiff() throws IOException {
        JsonNode declared = json(DECLARED);
        JsonNode live = json("""
                {"containers":[{"name":"app",
                  "image":{"pinned":true,"ref":"a:1","registry":"harbor.internal","repository":"payments/api"},
                  "security":{"run_as_non_root":true},
                  "resources":{"limits":{"memory":"1Gi"}}}]}
                """);
        assertNull(DriftComputer.configDrift(declared, live), "canonical JSON diff ignores key order");
    }

    @Test
    void partialLiveReportOnlyComparesObservedFields() throws IOException {
        JsonNode declared = json(DECLARED);
        JsonNode live = json("{\"containers\":[{\"name\":\"app\"}]}"); // agent saw nothing else
        assertNull(DriftComputer.configDrift(declared, live));
    }

    @Test
    void undeclaredLiveContainerIsDrift() throws IOException {
        JsonNode declared = json(DECLARED);
        JsonNode live = json("""
                {"containers":[
                  {"name":"app","image":{"repository":"payments/api","registry":"harbor.internal","ref":"a:1","pinned":true}},
                  {"name":"sidecar-miner","image":{"repository":"x","registry":"","ref":"x","pinned":false}}]}
                """);
        CheckResult r = DriftComputer.configDrift(declared, live);
        assertTrue(r.detail().contains("sidecar-miner"), r.detail());
    }

    @Test
    void topologyDriftIsEdgeSetSymmetricDiff() throws IOException {
        JsonNode captured = json("{\"nodes\":[\"a\",\"b\"],\"edges\":[{\"from\":\"a\",\"to\":\"b\"},{\"from\":\"a\",\"to\":\"c\"}]}");
        JsonNode declared = json("{\"nodes\":[\"a\",\"b\"],\"edges\":[{\"from\":\"a\",\"to\":\"b\"},{\"from\":\"b\",\"to\":\"a\"}]}");
        CheckResult r = DriftComputer.topologyDrift(captured, declared);
        assertEquals("DRIFT-TOPOLOGY", r.ruleId());
        assertEquals("TOPOLOGY_DRIFT", r.reasonCode());
        assertTrue(r.detail().contains("a->c"));
        assertTrue(r.detail().contains("b->a"));
    }

    @Test
    void equalEdgeSetsAreClean() throws IOException {
        JsonNode captured = json("{\"nodes\":[\"a\"],\"edges\":[{\"from\":\"a\",\"to\":\"b\",\"encrypted\":true}]}");
        JsonNode declared = json("{\"nodes\":[\"a\",\"b\"],\"edges\":[{\"from\":\"a\",\"to\":\"b\"}]}");
        assertNull(DriftComputer.topologyDrift(captured, declared),
                "edge identity is (from,to) — attributes belong to graph rules");
    }

    @Test
    void absentSnapshotsProduceNoVerdict() throws IOException {
        assertNull(DriftComputer.topologyDrift(null, json("{\"nodes\":[]}")));
        assertNull(DriftComputer.configDrift(json(DECLARED), null));
    }

    @Test
    void mergeLiveOverridesDeclaredPerField() throws IOException {
        JsonNode declared = json(DECLARED);
        JsonNode live = json("""
                {"containers":[{"name":"app","image":{"ref":"a:2","pinned":false}}],
                 "runtime_policy":{"tls_min":"1.2"}}
                """);
        JsonNode doc = DriftComputer.mergeDoc(declared, live);
        JsonNode image = doc.path("workload").path("containers").get(0).path("image");
        assertEquals("a:2", image.path("ref").asText(), "live wins");
        assertEquals("payments/api", image.path("repository").asText(), "declared fields survive");
        assertEquals("1.2", doc.path("runtime_policy").path("tls_min").asText());
        assertEquals(true, doc.path("workload").path("containers").get(0)
                .path("security").path("run_as_non_root").asBoolean(), "untouched sections survive");
    }
}
