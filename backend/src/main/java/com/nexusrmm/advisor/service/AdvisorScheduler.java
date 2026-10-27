package com.nexusrmm.advisor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Evaluates the advisor periodically so the alert log and issue ages stay
 * accurate while nobody has the UI open. Read-only with respect to the
 * computer: evaluation only reads stored telemetry and the process cache.
 */
@Component
public class AdvisorScheduler {

    private static final Logger log = LoggerFactory.getLogger(AdvisorScheduler.class);

    private final AdvisorFacade facade;

    public AdvisorScheduler(AdvisorFacade facade) {
        this.facade = facade;
    }

    @Scheduled(fixedDelayString = "${nexus.advisor.evaluation-interval-seconds:60}000", initialDelayString = "20000")
    public void evaluate() {
        try {
            facade.evaluate();
        } catch (RuntimeException e) {
            log.warn("Advisor evaluation failed: {}", e.toString());
        }
    }
}
