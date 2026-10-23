package com.nexusrmm.health.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.nexusrmm.health.analysis.HealthAnalysisEngine;
import com.nexusrmm.health.analysis.Scenarios;
import com.nexusrmm.health.config.HealthSettings;
import com.nexusrmm.health.model.HealthReport;
import com.nexusrmm.health.model.RiskReport;
import com.nexusrmm.health.risk.RiskEngine;
import com.nexusrmm.health.support.FakeHealthData;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.nexusrmm.health.support.FakeHealthData.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backend test area 21: the JSON the frontend reads. The mapper mirrors the
 * application's Jackson setup (spring.jackson.default-property-inclusion:
 * non_null; Instants as ISO-8601 strings). If a field the Health/Risk pages
 * depend on is renamed or retyped, this fails instead of the UI silently
 * rendering blanks.
 */
class HealthJsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .registerModule(new SimpleModule().addSerializer(Instant.class, ToStringSerializer.instance));

    private final HealthSettings settings = HealthSettings.defaults();
    private final HealthAnalysisEngine engine = new HealthAnalysisEngine(HealthAnalysisEngine.defaultAnalyzers(), settings);

    private JsonNode health(FakeHealthData d) throws Exception {
        HealthReport r = engine.analyze(d, NOW);
        return mapper.readTree(mapper.writeValueAsString(r));
    }

    private JsonNode risk(FakeHealthData d) throws Exception {
        HealthReport h = engine.analyze(d, NOW);
        RiskReport r = new RiskEngine(settings).assess(h, NOW);
        return mapper.readTree(mapper.writeValueAsString(r));
    }

    private static JsonNode byComponent(JsonNode root, String id) {
        for (JsonNode c : root.get("components")) {
            if (id.equals(c.get("component").asText())) return c;
        }
        throw new AssertionError("component " + id + " missing");
    }

    @Test
    void healthReportExposesTheFieldsTheHealthPageReads() throws Exception {
        JsonNode root = health(Scenarios.healthyWeek());

        assertEquals(100, root.get("overallScore").asInt());
        assertEquals("HEALTHY", root.get("overallStatus").asText());
        assertTrue(root.get("dataCoverage").isNumber());
        assertTrue(root.get("overallBasis").isTextual());
        assertEquals("2026-10-21T12:00:00Z", root.get("generatedAt").asText());
        assertEquals(8, root.get("components").size());
        assertTrue(root.get("limitations").isArray() && root.get("limitations").size() > 0);

        JsonNode cpu = byComponent(root, "CPU");
        for (String key : new String[] { "component", "label", "score", "status", "weight", "sampleCount", "dataCoverage", "basis",
                "instances", "findings" }) {
            assertTrue(cpu.has(key), "CPU component must carry '" + key + "'");
        }
        assertFalse(cpu.has("noDataReason"), "null fields are omitted, and the UI treats missing as null");
    }

    @Test
    void aNoDataComponentHasAReasonAndNoScoreKey() throws Exception {
        JsonNode battery = byComponent(health(Scenarios.healthyWeek()), "BATTERY");
        assertEquals("NO_DATA", battery.get("status").asText());
        assertFalse(battery.has("score"), "no score key at all - never 0");
        assertTrue(battery.get("noDataReason").asText().length() > 0);
    }

    @Test
    void instancesAndFindingsUseTheNamesTheCardsRender() throws Exception {
        FakeHealthData d = new FakeHealthData().recentConstant(com.nexusrmm.entity.MetricType.STORAGE_UTILIZATION, "C:\\", 97);
        JsonNode storage = byComponent(health(d), "STORAGE_CAPACITY");
        JsonNode inst = storage.get("instances").get(0);
        assertEquals("C:\\", inst.get("instance").asText(), "backslashes survive serialization");
        assertTrue(inst.has("score") && inst.has("status") && inst.has("metrics"));
        JsonNode finding = storage.get("findings").get(0);
        assertEquals("CRITICAL", finding.get("severity").asText());
        assertTrue(finding.has("code") && finding.has("message") && finding.has("evidence"));
    }

    @Test
    void riskReportExposesTheFieldsTheRiskPageReads() throws Exception {
        FakeHealthData d = new FakeHealthData().recentConstant(com.nexusrmm.entity.MetricType.STORAGE_UTILIZATION, "C:\\", 97);
        JsonNode root = risk(d);

        for (String key : new String[] { "overallRiskScore", "overallLevel", "confidence", "methodology", "generatedAt", "components" }) {
            assertTrue(root.has(key), "risk report must carry '" + key + "'");
        }
        JsonNode storage = byComponent(root, "STORAGE_CAPACITY");
        assertEquals("CRITICAL", storage.get("level").asText());
        assertEquals(82, storage.get("riskScore").asInt());
        JsonNode factor = storage.get("factors").get(0);
        assertTrue(factor.get("severity").isNumber());
        assertTrue(factor.has("code") && factor.has("description"));

        JsonNode network = byComponent(root, "NETWORK");
        assertEquals("NOT_ASSESSED", network.get("level").asText());
        assertFalse(network.has("riskScore"));
        assertTrue(network.get("notAssessedReason").isTextual());
    }
}
