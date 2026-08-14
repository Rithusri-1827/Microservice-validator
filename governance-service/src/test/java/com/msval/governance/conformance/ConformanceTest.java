package com.msval.governance.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.msval.governance.conformance.ConformanceLoader.Vector;
import com.msval.governance.engine.CheckResult;
import com.msval.governance.engine.Engine;
import com.msval.governance.engine.EvalContext;
import com.msval.governance.engine.OperatorRegistry;

/**
 * TASK-009 corpus runner — every shared vector against engine-java, replicating
 * tests/test_conformance_corpus.py::_run_vector exactly. TASK-010 gates the milestone
 * on this and the pytest run both being green (ADR-001, IF-015).
 *
 * <p>symbolic_capacity vectors are SKIPPED by design: Java's capability set is
 * "runtime = all except symbolic_capacity" (F9 rev 5) — the solver exists only in engine-py.
 */
class ConformanceTest {

    private static final Engine ENGINE = new Engine(OperatorRegistry.standard());

    static Stream<Vector> vectors() throws IOException {
        return ConformanceLoader.load().stream();
    }

    @Test
    void corpusLoadsAndIsBaselineComplete() throws IOException {
        List<Vector> vectors = ConformanceLoader.load();
        // Mirror of test_corpus_loads_and_ids_unique: >= 2 vectors per catalog operator (14 ops).
        assertTrue(vectors.size() >= 28,
                "every operator needs at least a pass and a fail vector; found " + vectors.size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void vector(Vector v) {
        Assumptions.assumeFalse("symbolic_capacity".equals(v.operator()),
                "symbolic_capacity is not in engine-java's capability set (F9): solver lives in engine-py only");

        EvalContext ctx = EvalContext.fromJson(v.context());
        List<CheckResult> results = ENGINE.evaluateRule(v.input(), v.rule(), ctx);

        assertFalse(results.isEmpty(), v.id() + ": no results");
        List<CheckResult> failed = results.stream().filter(r -> !r.passed()).toList();
        boolean passedOverall = failed.isEmpty();
        assertEquals(v.expect().pass(), passedOverall,
                v.id() + " (" + v.operator() + "): expected pass=" + v.expect().pass() + ", got " + results);
        if (!passedOverall && v.expect().reasonCode() != null) {
            assertEquals(v.expect().reasonCode(), failed.get(0).reasonCode(),
                    v.id() + ": expected " + v.expect().reasonCode() + ", got " + failed.get(0).reasonCode());
        }
        if (v.expect().path() != null) {
            assertTrue(results.stream().anyMatch(r -> r.path().equals(v.expect().path())),
                    v.id() + ": no result at path " + v.expect().path());
        }
    }
}
