package com.nexusrmm.health.provider;

import java.time.Instant;

/**
 * Whether "zero events" can be believed. Reliability is only scored when
 * monitoring is applicable and the last poll succeeded; otherwise an empty
 * event list means "unknown", not "no problems".
 */
public record EventMonitoring(boolean applicable, boolean lastPollSuccess, Instant lastPollAt, String detail) {
}
