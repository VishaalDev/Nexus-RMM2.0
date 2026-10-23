package com.nexusrmm.controller;

import com.nexusrmm.dto.HardwareEventDto;
import com.nexusrmm.repository.HardwareEventRepository;
import com.nexusrmm.service.DeviceIdentityService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class EventsController {

    private static final int MAX_LIMIT = 500;

    private final HardwareEventRepository repository;
    private final DeviceIdentityService deviceIdentityService;

    public EventsController(HardwareEventRepository repository, DeviceIdentityService deviceIdentityService) {
        this.repository = repository;
        this.deviceIdentityService = deviceIdentityService;
    }

    @GetMapping("/api/events")
    public List<HardwareEventDto> recent(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        String computerId = deviceIdentityService.getCurrent().getComputerId();
        var page = PageRequest.of(0, bounded, Sort.by("occurredAt").descending());
        return repository.findRecent(computerId, page).stream().map(HardwareEventDto::from).toList();
    }
}
