package com.nexusrmm.controller;

import com.nexusrmm.dto.CurrentMetricDto;
import com.nexusrmm.dto.HistoryPointDto;
import com.nexusrmm.entity.MetricType;
import com.nexusrmm.exception.BadRequestException;
import com.nexusrmm.service.MetricQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
public class MetricsController {

    private static final Duration MAX_WINDOW = Duration.ofDays(30);

    private final MetricQueryService metricQueryService;

    public MetricsController(MetricQueryService metricQueryService) {
        this.metricQueryService = metricQueryService;
    }

    /**
     * Current value for every (metric type, instance) combination that has
     * a fresh sample. Types/instances with no recent data — not
     * implemented yet, or genuinely unsupported on this machine — are
     * simply absent rather than faked; the frontend renders those as
     * "no data" per its own state handling.
     */
    @GetMapping("/api/metrics/current")
    public List<CurrentMetricDto> current() {
        return List.of(MetricType.values()).stream()
                .flatMap(type -> metricQueryService.currentValues(type).stream())
                .toList();
    }

    @GetMapping("/api/metrics/history")
    public List<HistoryPointDto> history(@RequestParam("metric") String metric,
                                          @RequestParam(value = "instance", required = false) String instance,
                                          @RequestParam(value = "window", defaultValue = "1h") String window) {
        MetricType type = parseMetricType(metric);
        Duration duration = parseWindow(window);
        return metricQueryService.history(type, instance, duration);
    }

    private MetricType parseMetricType(String raw) {
        try {
            return MetricType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown metric type: '" + raw + "'. Valid values: "
                    + java.util.Arrays.toString(MetricType.values()));
        }
    }

    /** Accepts 1h, 6h, 24h, 7d, 30d (case-insensitive), capped at 30 days. */
    private Duration parseWindow(String raw) {
        String w = raw.trim().toLowerCase();
        Duration duration;
        try {
            if (w.endsWith("h")) {
                duration = Duration.ofHours(Long.parseLong(w.substring(0, w.length() - 1)));
            } else if (w.endsWith("d")) {
                duration = Duration.ofDays(Long.parseLong(w.substring(0, w.length() - 1)));
            } else {
                throw new NumberFormatException("no recognized suffix");
            }
        } catch (NumberFormatException e) {
            throw new BadRequestException("Unrecognized window '" + raw + "'. Expected formats: 1h, 6h, 24h, 7d, 30d.");
        }

        if (duration.isNegative() || duration.isZero()) {
            throw new BadRequestException("Window must be a positive duration.");
        }
        if (duration.compareTo(MAX_WINDOW) > 0) {
            throw new BadRequestException("Window exceeds the maximum supported range of 30 days.");
        }
        return duration;
    }
}
