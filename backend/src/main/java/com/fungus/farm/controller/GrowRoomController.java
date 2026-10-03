package com.fungus.farm.controller;

import com.fungus.farm.common.ApiResponse;
import com.fungus.farm.dto.RoomStatusRequest;
import com.fungus.farm.dto.ShelfRequest;
import com.fungus.farm.service.RoomService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class GrowRoomController {
    private final RoomService service;

    public GrowRoomController(RoomService service) {
        this.service = service;
    }

    @GetMapping("/rooms")
    public ApiResponse<List<Map<String, Object>>> rooms() {
        return ApiResponse.success(service.rooms());
    }

    @GetMapping("/rooms/overview")
    public ApiResponse<Map<String, Integer>> overview() {
        return ApiResponse.success(service.overview());
    }

    @GetMapping("/rooms/{roomId}/racks")
    public ApiResponse<List<Map<String, Object>>> racks(@PathVariable Long roomId) {
        return ApiResponse.success(service.racks(roomId));
    }

    @PutMapping("/rooms/{roomId}/status")
    public ApiResponse<Void> changeStatus(@PathVariable Long roomId, @RequestBody RoomStatusRequest request) {
        service.changeStatus(roomId, request.status());
        return ApiResponse.success("菇房状态已更新", null);
    }

    @PostMapping("/racks/{rackId}/slots")
    public ApiResponse<Map<String, Long>> shelf(@PathVariable Long rackId, @RequestBody ShelfRequest request) {
        return ApiResponse.success("菌包已上架", Map.of("id", service.shelf(rackId, request)));
    }
}
