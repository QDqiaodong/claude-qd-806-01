package com.fungus.farm.controller;

import com.fungus.farm.common.ApiResponse;
import com.fungus.farm.dto.AllocationRequest;
import com.fungus.farm.dto.AllocationRevokeRequest;
import com.fungus.farm.service.BagAllocationService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bag-allocations")
public class BagAllocationController {
    private final BagAllocationService service;

    public BagAllocationController(BagAllocationService service) {
        this.service = service;
    }

    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(service.board());
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> create(@RequestBody AllocationRequest request) {
        Map<String, Object> result = service.create(request);
        Map<String, Object> allocation = (Map<String, Object>) result.get("allocation");
        String message = Boolean.TRUE.equals(result.get("duplicated"))
                ? "该请求已处理过，返回原入房记录"
                : "已建立入房记录并生成出菇批次 " + allocation.get("fruitingBatchNo");
        return ApiResponse.success(message, allocation);
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<Void> revoke(@PathVariable Long id, @RequestBody AllocationRevokeRequest request) {
        service.revoke(id, request);
        return ApiResponse.success("入房分配已撤回，数量已归还菌包批次", null);
    }
}
