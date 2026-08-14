package com.msval.governance.cdm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** TEST-002 (Java side): F2 resolver edges — mirror of tests/test_cdm.py::TestResolver. */
class PathResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode DOC = readDoc();

    private static JsonNode readDoc() {
        try {
            return MAPPER.readTree("""
                    {"workload": {"containers": [{"name": "a", "image": {"ref": "r:1"}},
                                                 {"name": "b"}],
                                  "replicas": 2},
                     "empty_list": []}
                    """);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void scalar() {
        List<Slice> got = PathResolver.resolve(DOC, "workload.replicas");
        assertEquals(1, got.size());
        assertEquals("workload.replicas", got.get(0).path());
        assertEquals(2, got.get(0).value().asInt());
    }

    @Test
    void wildcardFanout() {
        List<Slice> got = PathResolver.resolve(DOC, "workload.containers[].name");
        assertEquals(2, got.size());
        assertEquals("workload.containers[0].name", got.get(0).path());
        assertEquals("a", got.get(0).value().textValue());
        assertEquals("workload.containers[1].name", got.get(1).path());
        assertEquals("b", got.get(1).value().textValue());
    }

    @Test
    void missingFinalSegment() {
        List<Slice> got = PathResolver.resolve(DOC, "workload.missing");
        assertEquals(1, got.size());
        assertEquals("workload.missing", got.get(0).path());
        assertTrue(got.get(0).value().isMissingNode());
    }

    @Test
    void missingDeep() {
        List<Slice> got = PathResolver.resolve(DOC, "nope.deeper.still");
        assertEquals(1, got.size());
        assertEquals("nope", got.get(0).path());
        assertTrue(got.get(0).value().isMissingNode());
    }

    @Test
    void missingUnderWildcard() {
        List<Slice> got = PathResolver.resolve(DOC, "workload.containers[].image");
        assertEquals(2, got.size());
        assertEquals("r:1", got.get(0).value().path("ref").textValue());
        assertTrue(got.get(1).value().isMissingNode());
    }

    @Test
    void wildcardOverEmptyList() {
        assertEquals(List.of(), PathResolver.resolve(DOC, "empty_list[]"));
    }

    @Test
    void nonListUnderWildcard() {
        SelectorException e = assertThrows(SelectorException.class,
                () -> PathResolver.resolve(DOC, "workload[]"));
        assertEquals("BAD_SELECTOR", e.code());
    }

    @Test
    void depthLimit() {
        assertThrows(SelectorException.class,
                () -> PathResolver.resolve(DOC, "a.a.a.a.a.a.a.a.a")); // 9 segments
    }

    @Test
    void fanoutLimit() {
        ObjectNode big = MAPPER.createObjectNode();
        ArrayNode xs = big.putArray("xs");
        for (int i = 0; i < 257; i++) {
            xs.addObject().put("v", i);
        }
        SelectorException e = assertThrows(SelectorException.class,
                () -> PathResolver.resolve(big, "xs[].v"));
        assertEquals("FANOUT", e.code());
    }

    @Test
    void badSegment() {
        assertThrows(SelectorException.class, () -> PathResolver.resolve(DOC, "Bad-Segment"));
    }
}
