package com.nexusrmm.sync.config;

import com.nexusrmm.sync.SyncSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code nexus.sync.*}. Every value has a safe default; with no
 * destination the module runs in local-only mode (state NOT_CONFIGURED).
 * Credentials belong in the environment ({@code NEXUS_SYNC_AUTH_TOKEN}),
 * never in application.yml.
 */
@ConfigurationProperties(prefix = "nexus.sync")
public class SyncProperties {

    /** Endpoint that accepts POSTed batches (docs/sync.md). Blank = not configured. */
    private String destinationUrl;
    /** Optional bearer token. Never logged and never returned by the API. */
    private String authToken;
    private int batchSize = 200;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(30);
    private Duration backoffInitial = Duration.ofSeconds(5);
    private Duration backoffMax = Duration.ofMinutes(5);
    private double backoffMultiplier = 2.0;
    private int maxBatchesPerCycle = 50;
    private int enqueueChunkSize = 5000;
    private int pollIntervalSeconds = 10;
    /** Whether telemetry stored before Module 3 existed is also eligible for synchronization. */
    private boolean backfillExistingData = true;

    public SyncSettings toSettings() {
        return SyncSettings.of(destinationUrl, authToken, batchSize, connectTimeout, requestTimeout, backoffInitial,
                backoffMax, backoffMultiplier, maxBatchesPerCycle, enqueueChunkSize, backfillExistingData);
    }

    public String getDestinationUrl() { return destinationUrl; }
    public void setDestinationUrl(String destinationUrl) { this.destinationUrl = destinationUrl; }
    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public Duration getBackoffInitial() { return backoffInitial; }
    public void setBackoffInitial(Duration backoffInitial) { this.backoffInitial = backoffInitial; }
    public Duration getBackoffMax() { return backoffMax; }
    public void setBackoffMax(Duration backoffMax) { this.backoffMax = backoffMax; }
    public double getBackoffMultiplier() { return backoffMultiplier; }
    public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
    public int getMaxBatchesPerCycle() { return maxBatchesPerCycle; }
    public void setMaxBatchesPerCycle(int maxBatchesPerCycle) { this.maxBatchesPerCycle = maxBatchesPerCycle; }
    public int getEnqueueChunkSize() { return enqueueChunkSize; }
    public void setEnqueueChunkSize(int enqueueChunkSize) { this.enqueueChunkSize = enqueueChunkSize; }
    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
    public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }
    public boolean isBackfillExistingData() { return backfillExistingData; }
    public void setBackfillExistingData(boolean backfillExistingData) { this.backfillExistingData = backfillExistingData; }
}
