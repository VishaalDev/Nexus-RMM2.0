package com.nexusrmm.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Basic liveness endpoint. This is intentionally separate from the richer
 * /api/diagnostics endpoint that Module 3 adds later (provider status,
 * collection stats, etc.) — this one just answers "is the backend up".
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString()
        );
    }
}
