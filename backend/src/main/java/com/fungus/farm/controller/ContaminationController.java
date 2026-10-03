package com.fungus.farm.controller;

import com.fungus.farm.common.ApiResponse;
import com.fungus.farm.dto.ContaminationRequest;
import com.fungus.farm.service.ContaminationService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contaminations")
public class ContaminationController {
    private final ContaminationService service;

    public ContaminationController(ContaminationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/summary")
    public ApiResponse<List<Map<String, Object>>> summary() {
        return ApiResponse.success(service.summary());
    }

    @PostMapping
    public ApiResponse<Map<String, Long>> register(@RequestBody ContaminationRequest request) {
        return ApiResponse.success("污染处理已登记", Map.of("id", service.register(request)));
    }

    @PostMapping("/bags/{bagBatchId}/discard")
    public ApiResponse<Void> discard(@PathVariable Long bagBatchId) {
        service.discard(bagBatchId);
        return ApiResponse.success("污染菌包已报废", null);
    }
}
