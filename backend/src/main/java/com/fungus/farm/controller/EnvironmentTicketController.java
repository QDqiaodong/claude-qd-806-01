package com.fungus.farm.controller;

import com.fungus.farm.common.ApiResponse;
import com.fungus.farm.dto.TicketClaimRequest;
import com.fungus.farm.dto.TicketNoteRequest;
import com.fungus.farm.dto.TicketResolveRequest;
import com.fungus.farm.service.EnvironmentTicketService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/environment-tickets")
public class EnvironmentTicketController {
    private final EnvironmentTicketService service;

    public EnvironmentTicketController(EnvironmentTicketService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> tickets(@RequestParam(required = false) String status) {
        return ApiResponse.success(service.tickets(status));
    }

    @PostMapping("/{ticketId}/claim")
    public ApiResponse<Void> claim(@PathVariable Long ticketId, @RequestBody TicketClaimRequest request) {
        service.claim(ticketId, request);
        return ApiResponse.success("处置单已接单", null);
    }

    @PostMapping("/{ticketId}/notes")
    public ApiResponse<Void> addNote(@PathVariable Long ticketId, @RequestBody TicketNoteRequest request) {
        service.addNote(ticketId, request);
        return ApiResponse.success("处理记录已保存", null);
    }

    @PostMapping("/{ticketId}/resolve")
    public ApiResponse<Void> resolve(@PathVariable Long ticketId, @RequestBody TicketResolveRequest request) {
        service.resolve(ticketId, request);
        return ApiResponse.success("处置单已解除，相关批次已恢复生产", null);
    }
}
