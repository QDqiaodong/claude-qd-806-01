package com.fungus.farm.controller;

import com.fungus.farm.common.ApiResponse;
import com.fungus.farm.dto.RoomEntryRequest;
import com.fungus.farm.dto.RoomEntryWithdrawRequest;
import com.fungus.farm.service.RoomEntryService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 菌包开袋后的分批入房：查去向、整批/拆次分配、撤回未开始出菇的在制分配。
 */
@RestController
@RequestMapping("/api/room-entries")
public class RoomEntryController {
    private final RoomEntryService service;

    public RoomEntryController(RoomEntryService service) {
        this.service = service;
    }

    /** 各菌包批次的原始总量、已分配、剩余可用与去向（在制/已撤回/历史批次） */
    @GetMapping("/assignments")
    public ApiResponse<Map<String, Object>> assignments() {
        return ApiResponse.success(service.assignments());
    }

    /** 已开袋批次整批或拆一次数量入房，同时建立对应的出菇批次 */
    @PostMapping("/bags/{bagId}")
    public ApiResponse<Map<String, Object>> assign(@PathVariable Long bagId, @RequestBody RoomEntryRequest request) {
        return ApiResponse.success("入房已登记，出菇批次已建立", service.assign(bagId, request));
    }

    /** 撤回一次在制分配：删除出菇批次并归还数量（已采收/环境暂停/有关联记录的会被拒绝并说明原因） */
    @PostMapping("/{entryId}/withdraw")
    public ApiResponse<Void> withdraw(@PathVariable Long entryId, @RequestBody(required = false) RoomEntryWithdrawRequest request) {
        service.withdraw(entryId, request == null ? null : request.operator());
        return ApiResponse.success("已撤回入房，菌包数量已归还", null);
    }
}
