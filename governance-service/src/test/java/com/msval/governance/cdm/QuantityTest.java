package com.msval.governance.cdm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** TEST-002 (Java side): F3 quantity edges — mirror of tests/test_cdm.py::TestQuantity. */
class QuantityTest {

    private static final long KI = 1024L;

    @ParameterizedTest
    @CsvSource({
            "1Gi,   memory, 1073741824",
            "1G,    memory, 1000000000",
            "0.5,   cpu,    500",
            "250m,  cpu,    250",
            "2,     cpu,    2000",
            "512Mi, memory, 536870912",
    })
    void parse(String qty, String dim, long expected) {
        assertEquals(expected, Quantity.parse(qty, dim));
    }

    @Test
    void binaryVsDecimalUnitsDiffer() {
        assertNotEquals(Quantity.parse("1G", "memory"), Quantity.parse("1Gi", "memory"));
        assertEquals(KI * KI * KI, Quantity.parse("1Gi", "memory"));
    }

    @ParameterizedTest
    @CsvSource({
            "-1,   cpu",
            "100m, memory",
            "1Gi,  cpu",
            "abc,  memory",
            "'',   cpu",
    })
    void invalid(String qty, String dim) {
        assertThrows(QuantityException.class, () -> Quantity.parse(qty, dim));
    }

    @Test
    void unknownDimensionRefused() {
        assertThrows(QuantityException.class, () -> Quantity.parse("1", "disk"));
    }

    @Test
    void expr() {
        assertEquals(1000, Quantity.parseExpr("100m + 0.9", "cpu"));
        assertEquals(KI * KI * KI + 256 * KI * KI, Quantity.parseExpr("1Gi + 256Mi", "memory"));
        assertEquals(KI * KI * KI - 256 * KI * KI, Quantity.parseExpr("1Gi - 256Mi", "memory"));
    }

    @Test
    void exprEmptyRefused() {
        assertThrows(QuantityException.class, () -> Quantity.parseExpr("", "cpu"));
    }
}
