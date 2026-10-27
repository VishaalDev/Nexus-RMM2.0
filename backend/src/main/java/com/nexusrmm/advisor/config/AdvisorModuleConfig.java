package com.nexusrmm.advisor.config;

import com.nexusrmm.advisor.engine.AdvisorEngine;
import com.nexusrmm.advisor.engine.AlertTracker;
import com.nexusrmm.advisor.service.AdvisorFacade;
import com.nexusrmm.advisor.service.ProcessSamplerService;
import com.nexusrmm.health.service.HealthFacade;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires Module 4 the same way HealthModuleConfig wires Module 2: the engine,
 * tracker and facade are plain classes assembled here, so they stay
 * unit-testable and no existing bean is modified.
 */
@Configuration
@EnableConfigurationProperties(AdvisorProperties.class)
public class AdvisorModuleConfig {

    @Bean
    public AdvisorSettings advisorSettings(AdvisorProperties properties) {
        return properties.toSettings();
    }

    @Bean
    public AdvisorEngine advisorEngine(AdvisorSettings settings) {
        return new AdvisorEngine(settings);
    }

    @Bean
    public AlertTracker alertTracker(AdvisorSettings settings) {
        return new AlertTracker(settings.resolveAfterAbsent(), settings.maxAlertEvents());
    }

    @Bean
    public AdvisorFacade advisorFacade(HealthFacade health, ProcessSamplerService processes, AdvisorEngine engine,
                                       AlertTracker tracker, AdvisorSettings settings) {
        return new AdvisorFacade(health::analysis, processes::latest, engine, tracker, settings, Clock.systemUTC());
    }
}
