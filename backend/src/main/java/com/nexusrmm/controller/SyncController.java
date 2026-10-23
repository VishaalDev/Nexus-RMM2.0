package com.nexusrmm.controller;

import com.nexusrmm.sync.SyncCoordinator;
import com.nexusrmm.sync.SyncStatus;
import com.nexusrmm.sync.SyncTriggerResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

/** Module 3 API. See docs/api-sync.md. */
@RestController
public class SyncController {

    private final SyncCoordinator coordinator;

    public SyncController(SyncCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @GetMapping("/api/sync/status")
    public SyncStatus status() throws SQLException {
        return coordinator.status();
    }

    /**
     * 202 started (runs in the background; poll /api/sync/status),
     * 409 already running / not configured / invalid configuration.
     */
    @PostMapping("/api/sync/trigger")
    public ResponseEntity<SyncTriggerResponse> trigger() throws SQLException {
        SyncTriggerResponse response = coordinator.trigger();
        HttpStatus status = "STARTED".equals(response.outcome()) ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(response);
    }
}
