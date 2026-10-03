package com.fungus.farm.controller;

import com.fungus.farm.common.ApiResponse;
import com.fungus.farm.domain.BagBatch;
import com.fungus.farm.service.BagService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bags")
public class BagController {
    private final BagService service;

    public BagController(BagService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<BagBatch>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(service.list(status));
    }

    @GetMapping("/summary")
    public ApiResponse<List<Map<String, Object>>> summary() {
        return ApiResponse.success(service.summary());
    }

    @PostMapping("/{id}/open")
    public ApiResponse<Void> open(@PathVariable Long id) {
        service.open(id);
        return ApiResponse.success("菌包已开袋", null);
    }
}
