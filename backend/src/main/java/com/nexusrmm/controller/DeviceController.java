package com.nexusrmm.controller;

import com.nexusrmm.dto.DeviceDto;
import com.nexusrmm.service.DeviceIdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeviceController {

    private final DeviceIdentityService deviceIdentityService;

    public DeviceController(DeviceIdentityService deviceIdentityService) {
        this.deviceIdentityService = deviceIdentityService;
    }

    @GetMapping("/api/device")
    public DeviceDto device() {
        return DeviceDto.from(deviceIdentityService.getCurrent());
    }
}
