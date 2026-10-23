package com.nexusrmm.health.web;

import com.nexusrmm.exception.BadRequestException;
import com.nexusrmm.health.analysis.HealthAnalysisEngine;
import com.nexusrmm.health.analysis.Scenarios;
import com.nexusrmm.health.config.HealthSettings;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.risk.RiskEngine;
import com.nexusrmm.health.service.HealthFacade;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static com.nexusrmm.health.support.FakeHealthData.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backend test area 16: controllers. Invoked directly (the controllers are
 * deliberately thin delegates), which verifies request parsing and
 * error mapping; HTTP routing itself is Spring's responsibility.
 */
class HealthControllersTest {

    private final HealthSettings settings = HealthSettings.defaults();
    private final HealthFacade facade = new HealthFacade(Scenarios.healthyWeek(),
            new HealthAnalysisEngine(HealthAnalysisEngine.defaultAnalyzers(), settings), new RiskEngine(settings), settings,
            Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    private final HealthAnalysisController health = new HealthAnalysisController(facade);
    private final RiskController risk = new RiskController(facade);

    @Test
    void summaryReportAndComponentListAreServedFromTheFacade() {
        assertEquals(100, health.summary().overallScore());
        assertEquals(8, health.components().size());
        assertEquals(8, health.report().components().size());
    }

    @Test
    void componentPathAcceptsLowerCaseAndHyphenatedNames() {
        assertEquals(ComponentId.STORAGE_CAPACITY, health.component("storage-capacity").component());
        assertEquals(ComponentId.CPU, health.component("cpu").component());
        assertEquals(ComponentId.CPU, health.component(" CPU ").component());
        assertEquals(ComponentId.STORAGE_CAPACITY, risk.componentRisk("storage_capacity").component());
    }

    @Test
    void anUnknownComponentIsABadRequestListingTheValidValues() {
        BadRequestException ex = assertThrows(BadRequestException.class, () -> health.component("gpu"));
        assertTrue(ex.getMessage().contains("Unknown component: 'gpu'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("STORAGE_CAPACITY"), "valid values must be listed");
        assertThrows(BadRequestException.class, () -> risk.componentRisk("nope"));
    }

    @Test
    void riskEndpointsReturnTheRiskReport() {
        assertEquals(0, risk.risk().overallRiskScore());
        assertEquals(8, risk.risk().components().size());
    }
}
