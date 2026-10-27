package com.nexusrmm.advisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code nexus.advisor.*} (see application.yml). A plain mutable
 * bean (like NexusProperties) with defaults equal to the shipped
 * application.yml. {@link #toSettings()} converts into the framework-free,
 * validated {@link AdvisorSettings}; {@link #validate()} checks the
 * scheduling values that @Scheduled would otherwise read unchecked, so a
 * typo fails startup instead of creating a busy loop.
 */
@ConfigurationProperties(prefix = "nexus.advisor")
public class AdvisorProperties {

    private boolean processSamplingEnabled = true;
    private int processIntervalSeconds = 30;
    private int evaluationIntervalSeconds = 60;
    private int processTopN = 8;
    private double minBlameCpuPercent = 5.0;
    private double minBlameMemoryPercent = 5.0;
    private double dominantShare = 0.5;
    private int longUptimeDays = 14;
    private int resolveAfterAbsent = 2;
    private int maxAlertEvents = 200;
    private int topActions = 3;
    private int cacheTtlSeconds = 10;

    public void validate() {
        if (processIntervalSeconds < 5 || processIntervalSeconds > 3600) {
            throw new IllegalArgumentException(
                    "Invalid nexus.advisor configuration: process-interval-seconds must be 5..3600");
        }
        if (evaluationIntervalSeconds < 10 || evaluationIntervalSeconds > 3600) {
            throw new IllegalArgumentException(
                    "Invalid nexus.advisor configuration: evaluation-interval-seconds must be 10..3600");
        }
    }

    public AdvisorSettings toSettings() {
        validate();
        return new AdvisorSettings(processTopN, minBlameCpuPercent, minBlameMemoryPercent, dominantShare,
                longUptimeDays, resolveAfterAbsent, maxAlertEvents, topActions, Duration.ofSeconds(cacheTtlSeconds));
    }

    public boolean isProcessSamplingEnabled() {
        return processSamplingEnabled;
    }

    public void setProcessSamplingEnabled(boolean v) {
        this.processSamplingEnabled = v;
    }

    public int getProcessIntervalSeconds() {
        return processIntervalSeconds;
    }

    public void setProcessIntervalSeconds(int v) {
        this.processIntervalSeconds = v;
    }

    public int getEvaluationIntervalSeconds() {
        return evaluationIntervalSeconds;
    }

    public void setEvaluationIntervalSeconds(int v) {
        this.evaluationIntervalSeconds = v;
    }

    public int getProcessTopN() {
        return processTopN;
    }

    public void setProcessTopN(int v) {
        this.processTopN = v;
    }

    public double getMinBlameCpuPercent() {
        return minBlameCpuPercent;
    }

    public void setMinBlameCpuPercent(double v) {
        this.minBlameCpuPercent = v;
    }

    public double getMinBlameMemoryPercent() {
        return minBlameMemoryPercent;
    }

    public void setMinBlameMemoryPercent(double v) {
        this.minBlameMemoryPercent = v;
    }

    public double getDominantShare() {
        return dominantShare;
    }

    public void setDominantShare(double v) {
        this.dominantShare = v;
    }

    public int getLongUptimeDays() {
        return longUptimeDays;
    }

    public void setLongUptimeDays(int v) {
        this.longUptimeDays = v;
    }

    public int getResolveAfterAbsent() {
        return resolveAfterAbsent;
    }

    public void setResolveAfterAbsent(int v) {
        this.resolveAfterAbsent = v;
    }

    public int getMaxAlertEvents() {
        return maxAlertEvents;
    }

    public void setMaxAlertEvents(int v) {
        this.maxAlertEvents = v;
    }

    public int getTopActions() {
        return topActions;
    }

    public void setTopActions(int v) {
        this.topActions = v;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(int v) {
        this.cacheTtlSeconds = v;
    }
}
