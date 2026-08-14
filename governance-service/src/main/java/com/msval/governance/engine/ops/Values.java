package com.msval.governance.engine.ops;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * Shared value semantics for operators — mirrors Python's `==`, `type(a) is type(b)`,
 * `dict.get`, and `str()` closely enough for conformance parity.
 */
final class Values {

    private Values() {
    }

    /** KeyError mirror: params[key] must exist (explicit null is a value, absence is an error). */
    static JsonNode req(JsonNode params, String key) {
        if (params == null || !params.has(key)) {
            throw new IllegalArgumentException("KeyError: '" + key + "'");
        }
        return params.get(key);
    }

    /** dict.get(key) mirror: absent key → None (NullNode). */
    static JsonNode get(JsonNode obj, String key) {
        JsonNode n = obj.get(key);
        return n == null ? NullNode.getInstance() : n;
    }

    /**
     * Python `==` over JSON values: numbers compare across int/float, bools equal 1/0,
     * containers compare element-wise, otherwise same-kind equality.
     */
    static boolean pyEquals(JsonNode a, JsonNode b) {
        a = a == null ? NullNode.getInstance() : a;
        b = b == null ? NullNode.getInstance() : b;
        if (a.isBoolean() || b.isBoolean()) {
            if (a.isBoolean() && b.isBoolean()) {
                return a.booleanValue() == b.booleanValue();
            }
            // Python: True == 1, False == 0
            if (a.isBoolean() && b.isNumber()) {
                return numeric(a).compareTo(b.decimalValue()) == 0;
            }
            if (b.isBoolean() && a.isNumber()) {
                return numeric(b).compareTo(a.decimalValue()) == 0;
            }
            return false;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isTextual() && b.isTextual()) {
            return a.textValue().equals(b.textValue());
        }
        if (a.isNull() || b.isNull()) {
            return a.isNull() && b.isNull();
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!pyEquals(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> it = a.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!b.has(e.getKey()) || !pyEquals(e.getValue(), b.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** Python `a == b and type(a) is type(b)`: value kinds must match at the top level. */
    static boolean typeStrictEquals(JsonNode a, JsonNode b) {
        return kind(a) == kind(b) && pyEquals(a, b);
    }

    /** Python str() close enough for set-membership parity (unique_by). */
    static String pyStr(JsonNode n) {
        if (n == null || n.isNull()) {
            return "None";
        }
        if (n.isBoolean()) {
            return n.booleanValue() ? "True" : "False";
        }
        if (n.isTextual()) {
            return n.textValue();
        }
        if (n.isNumber()) {
            return n.asText();
        }
        return n.toString();
    }

    private static BigDecimal numeric(JsonNode bool) {
        return bool.booleanValue() ? BigDecimal.ONE : BigDecimal.ZERO;
    }

    private enum Kind { NULL, BOOL, INT, FLOAT, TEXT, ARRAY, OBJECT, OTHER }

    private static Kind kind(JsonNode n) {
        if (n == null || n.isNull()) {
            return Kind.NULL;
        }
        if (n.isBoolean()) {
            return Kind.BOOL;
        }
        if (n.isIntegralNumber()) {
            return Kind.INT;
        }
        if (n.isFloatingPointNumber()) {
            return Kind.FLOAT;
        }
        if (n.isTextual()) {
            return Kind.TEXT;
        }
        if (n.isArray()) {
            return Kind.ARRAY;
        }
        if (n.isObject()) {
            return Kind.OBJECT;
        }
        return Kind.OTHER;
    }
}
