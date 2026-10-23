package com.nexusrmm.health.config;

import com.nexusrmm.config.NexusProperties;
import com.nexusrmm.health.analysis.HealthAnalysisEngine;
import com.nexusrmm.health.provider.HealthDataProvider;
import com.nexusrmm.health.risk.RiskEngine;
import com.nexusrmm.health.service.HealthFacade;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Wires Module 2. The engines and facade are plain classes (no Spring
 * annotations of their own), assembled here, so they stay unit-testable and
 * Module 1's beans are never modified. The collection interval is read
 * from the existing NexusProperties so "expected samples per window" can
 * never disagree with what the collector actually does.
 */
@Configuration
@EnableConfigurationProperties(HealthProperties.class)
public class HealthModuleConfig {

    @Bean
    public HealthSettings healthSettings(HealthProperties properties, NexusProperties nexus) {
        return properties.toSettings(Duration.ofSeconds(Math.max(1, nexus.getCollection().getIntervalSeconds())));
    }

    @Bean
    public HealthAnalysisEngine healthAnalysisEngine(HealthSettings settings) {
        return new HealthAnalysisEngine(HealthAnalysisEngine.defaultAnalyzers(), settings);
    }

    @Bean
    public RiskEngine riskEngine(HealthSettings settings) {
        return new RiskEngine(settings);
    }

    @Bean
    public HealthFacade healthFacade(HealthDataProvider provider, HealthAnalysisEngine engine, RiskEngine riskEngine,
                                     HealthSettings settings) {
        return new HealthFacade(provider, engine, riskEngine, settings, Clock.systemUTC());
    }
}
