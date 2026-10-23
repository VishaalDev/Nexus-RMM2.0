package com.nexusrmm.health.provider;

import java.time.Instant;

/**
 * Time-bucket aggregate of one series. {@code sampleCount} counts every
 * stored row in the bucket (including unavailable ones); {@code valueCount}
 * only those that carried a real value, which are the only ones averaged.
 */
public record BucketPoint(Instant start, double avg, double min, double max, int sampleCount, int valueCount) {
}
