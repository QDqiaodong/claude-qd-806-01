package com.fungus.farm.controller;

import com.fungus.farm.common.ApiResponse;
import com.fungus.farm.domain.EnvironmentRecord;
import com.fungus.farm.dto.EnvironmentRecordRequest;
import com.fungus.farm.service.EnvironmentService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/environments")
public class EnvironmentController {
    private final EnvironmentService service;

    public EnvironmentController(EnvironmentService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public ApiResponse<List<Map<String, Object>>> dashboard() {
        return ApiResponse.success(service.dashboard());
    }

    @GetMapping
    public ApiResponse<List<EnvironmentRecord>> records(
            @RequestParam(required = false) Long roomId,
            @RequestParam(required = false) Boolean overLimit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.success(service.records(roomId, overLimit, from, to));
    }

    @GetMapping("/rooms/{roomId}/history")
    public ApiResponse<List<EnvironmentRecord>> history(@PathVariable Long roomId,
            @RequestParam(defaultValue = "24") int hours) {
        return ApiResponse.success(service.history(roomId, hours));
    }

    @PostMapping
    public ApiResponse<Map<String, Long>> add(@RequestBody EnvironmentRecordRequest request) {
        return ApiResponse.success("环境记录已保存", Map.of("id", service.add(request)));
    }
}
