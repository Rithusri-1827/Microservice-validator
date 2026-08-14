package com.msval.governance.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.persist.AuditRepo;
import com.msval.governance.persist.BundleRepo;
import com.msval.governance.persist.Jsonb;
import com.msval.governance.registry.RegistryService;
import com.msval.governance.registry.RuleCache;
import com.msval.governance.support.HttpError;

/** DD-012 — publish (idempotent by hash), activate (pointer flip + cache swap), fetch routing. */
class RegistryIntegrationTest {

    private final JdbcTemplate jdbc = TestDb.jdbc();
    private final BundleRepo bundles = new BundleRepo(jdbc);
    private final RuleCache cache = new RuleCache(bundles);
    private final RegistryService registry =
            new RegistryService(bundles, new AuditRepo(jdbc), cache, TestDb.txn());

    @BeforeEach
    void clean() {
        TestDb.truncate();
        cache.swap();
    }

    private static JsonNode bundle(String version, String ruleJson) throws Exception {
        return Jsonb.MAPPER.readTree("""
                {"manifest":{"version":"%s","grammar_version":"1","git_commit":"abc","rule_count":1},
                 "stage_sets":{"ci":[%s],"intake":[],"runtime":[%s]}}
                """.formatted(version, ruleJson, ruleJson));
    }

    private static final String RULE = """
            {"id":"SEC-002","family":"security","title":"pinned images",
             "target":"workload.containers[].image.pinned","operator":"equals",
             "params":{"value":true},"severity":"BLOCK","phases":["ci","runtime"],
             "environments":["*"],"message":"not pinned"}
            """;

    @Test
    void publishIsIdempotentByContentHash() throws Exception {
        RegistryService.PublishResult first = registry.publish(bundle("vaaa111", RULE));
        assertTrue(first.created());
        assertEquals("vaaa111", first.version());

        // same content, republished (even under another label) ⇒ 200 with the stored version
        RegistryService.PublishResult again = registry.publish(bundle("vaaa111", RULE));
        assertEquals("vaaa111", again.version());
        assertTrue(!again.created());

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM publish_audit WHERE action = 'publish'", Integer.class));
    }

    @Test
    void activateFlipsAllStagesAndSwapsTheCache() throws Exception {
        registry.publish(bundle("vbbb222", RULE));
        assertEquals(0, cache.ruleSetFor("runtime").rules().size(), "not active until activated");

        Map<String, String> stages = registry.activate("vbbb222");
        assertEquals("vbbb222", stages.get("ci"));
        assertEquals("vbbb222", stages.get("intake"));
        assertEquals("vbbb222", stages.get("runtime"));

        assertEquals(1, cache.ruleSetFor("runtime").rules().size());
        assertEquals(1, cache.ruleSetFor("ci").rules().size());
        assertEquals(0, cache.ruleSetFor("intake").rules().size(),
                "equals-op rule with phases [ci,runtime] never routes to intake");

        RegistryService.BundleView active = registry.active("runtime");
        assertEquals("vbbb222", active.version());
        assertEquals("SEC-002", active.rules().get(0).path("id").asText());

        assertEquals(404, assertThrows(HttpError.class,
                () -> registry.activate("v-nope")).status());
    }

    @Test
    void reservedSyntheticPrefixesAreRejected() throws Exception {
        String reserved = RULE.replace("SEC-002", "DRIFT-CONFIG");
        HttpError e = assertThrows(HttpError.class,
                () -> registry.publish(bundle("vccc333", reserved)));
        assertEquals(422, e.status());
    }

    @Test
    void unroutableRuleIs422ListingIds() throws Exception {
        String badOp = RULE.replace("\"operator\":\"equals\"", "\"operator\":\"quantum_vibes\"");
        RegistryService.UnroutableBundle e = assertThrows(RegistryService.UnroutableBundle.class,
                () -> registry.publish(bundle("vddd444", badOp)));
        assertEquals(java.util.List.of("SEC-002"), e.unroutable);
    }

    @Test
    void byVersionRoutesRulesPerStageCapability() throws Exception {
        String symbolic = """
                {"id":"RES-001","family":"resource","title":"ram claim",
                 "target":"resource_claims.ram_expr","operator":"symbolic_capacity",
                 "params":{"dimension":"memory"},"severity":"BLOCK","phases":["ci","intake"],
                 "environments":["*"],"message":"m"}
                """;
        JsonNode b = Jsonb.MAPPER.readTree("""
                {"manifest":{"version":"veee555","grammar_version":"1"},
                 "stage_sets":{"ci":[%s,%s],"intake":[%s],"runtime":[%s]}}
                """.formatted(RULE, symbolic, symbolic, RULE));
        registry.publish(b);
        assertEquals(1, registry.byVersion("veee555", "intake").rules().size(),
                "symbolic_capacity routes to intake");
        assertEquals(1, registry.byVersion("veee555", "runtime").rules().size(),
                "runtime excludes symbolic_capacity");
        assertEquals(2, registry.byVersion("veee555", "ci").rules().size());
        assertEquals(404, assertThrows(HttpError.class,
                () -> registry.byVersion("v-none", "ci")).status());
    }
}
