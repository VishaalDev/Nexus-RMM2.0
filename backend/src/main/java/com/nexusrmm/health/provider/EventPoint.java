package com.nexusrmm.health.provider;

import java.time.Instant;

/** A stored hardware event, reduced to the fields the reliability analysis uses. */
public record EventPoint(Instant time, String category, String level, String providerName, Integer eventId) {
}
