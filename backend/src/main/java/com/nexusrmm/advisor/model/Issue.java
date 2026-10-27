package com.nexusrmm.advisor.model;

import com.nexusrmm.health.model.Confidence;
import com.nexusrmm.health.model.Severity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One thing worth the person's attention, with the evidence behind it and
 * what to do about it.
 *
 * @param key             stable identity across evaluations (e.g. {@code cpu:load},
 *                        {@code storage:C:\}); used for lifecycle tracking
 * @param category        the health component name (CPU, MEMORY, ...) or VISIBILITY for monitoring gaps
 * @param sourceCodes     codes of the Module 2 findings this issue was derived from
 * @param evidence        the numbers behind the summary
 * @param priority        sort key, higher = more urgent (relative, not a probability)
 * @param firstSeen       when the advisor first observed this issue in this run (null until tracked)
 * @param lastSeen        most recent evaluation that still showed it (null until tracked)
 */
public record Issue(
        String key,
        String category,
        Severity severity,
        String title,
        String summary,
        List<String> sourceCodes,
        Map<String, Object> evidence,
        List<Recommendation> recommendations,
        Confidence confidence,
        int priority,
        Instant firstSeen,
        Instant lastSeen
) {
    public Issue {
        sourceCodes = sourceCodes == null ? List.of() : List.copyOf(sourceCodes);
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }

    public Issue tracked(Instant first, Instant last) {
        return new Issue(key, category, severity, title, summary, sourceCodes, evidence, recommendations,
                confidence, priority, first, last);
    }
}
