package com.nexusrmm.controller;

import com.nexusrmm.dto.HardwareSnapshotDto;
import com.nexusrmm.service.HardwareSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HardwareController {

    private final HardwareSnapshotService hardwareSnapshotService;

    public HardwareController(HardwareSnapshotService hardwareSnapshotService) {
        this.hardwareSnapshotService = hardwareSnapshotService;
    }

    @GetMapping("/api/device/hardware")
    public HardwareSnapshotDto hardware() {
        return hardwareSnapshotService.snapshot();
    }
}
