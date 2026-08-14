package com.msval.governance.findings;

import java.util.ArrayList;
import java.util.List;

/** IF-014 — what one applyEvaluation changed; the caller derives alerts from this. */
public final class FindingsDelta {

    /** One touched finding; {@code alert} follows the matrix (WAIVED occ++ stays silent). */
    public record Touched(long id, String ruleId, String serviceId, String version,
            String environment, String status, String severity, boolean alert, String message) {
    }

    public final List<Touched> opened = new ArrayList<>();
    public final List<Touched> updated = new ArrayList<>();
    public final List<Touched> autoResolved = new ArrayList<>();

    public FindingsDelta merge(FindingsDelta other) {
        opened.addAll(other.opened);
        updated.addAll(other.updated);
        autoResolved.addAll(other.autoResolved);
        return this;
    }
}
