package com.fungus.farm.service;

import com.fungus.farm.common.BizException;
import com.fungus.farm.common.EnvironmentLimits;
import com.fungus.farm.domain.EnvironmentRecord;
import com.fungus.farm.domain.GrowRoom;
import com.fungus.farm.dto.EnvironmentRecordRequest;
import com.fungus.farm.repository.EnvironmentRepository;
import com.fungus.farm.repository.GrowRoomRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnvironmentService {
    private final EnvironmentRepository environmentRepository;
    private final GrowRoomRepository roomRepository;
    private final EnvironmentTicketService ticketService;

    public EnvironmentService(EnvironmentRepository environmentRepository, GrowRoomRepository roomRepository,
            EnvironmentTicketService ticketService) {
        this.environmentRepository = environmentRepository;
        this.roomRepository = roomRepository;
        this.ticketService = ticketService;
    }

    public List<Map<String, Object>> dashboard() {
        return environmentRepository.findDashboard();
    }

    public List<EnvironmentRecord> records(Long roomId, Boolean overLimit, LocalDateTime from, LocalDateTime to) {
        return environmentRepository.findRecords(roomId, overLimit, from, to);
    }

    public List<EnvironmentRecord> history(Long roomId, int hours) {
        room(roomId);
        return environmentRepository.findHistory(roomId, Math.max(1, Math.min(hours, 168)));
    }

    @Transactional
    public Long add(EnvironmentRecordRequest request) {
        if (request.roomId() == null || request.temperature() == null || request.humidity() == null || request.co2Ppm() == null) {
            throw new BizException("菇房、温度、湿度和 CO₂ 均不能为空");
        }
        GrowRoom room = room(request.roomId());
        LocalDateTime recordedAt = request.recordedAt() == null ? LocalDateTime.now() : request.recordedAt();
        // 同一菇房同一采集时间的读数只入库一次：重复上报直接返回原记录，不重复推进处置流程
        Optional<EnvironmentRecord> existing = environmentRepository.findByRoomAndRecordedAt(room.getId(), recordedAt);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        EnvironmentRecord record = new EnvironmentRecord();
        record.setRoomId(request.roomId());
        record.setTemperature(request.temperature());
        record.setHumidity(request.humidity());
        record.setCo2Ppm(request.co2Ppm());
        record.setLightLux(request.lightLux() == null ? 0 : request.lightLux());
        record.setRecordedAt(recordedAt);
        record.setOverLimit(EnvironmentLimits.isOverLimit(room.getSpecies(), record));
        Long id;
        try {
            id = environmentRepository.save(record);
        } catch (DuplicateKeyException e) {
            // 并发重复上报：以已入库的读数为准，不再重复触发处置逻辑
            return environmentRepository.lockByRoomAndRecordedAt(room.getId(), recordedAt)
                    .map(EnvironmentRecord::getId)
                    .orElseThrow(() -> e);
        }
        record.setId(id);
        ticketService.processReading(room, record);
        return id;
    }

    private GrowRoom room(Long id) {
        return roomRepository.findById(id).orElseThrow(() -> new BizException("菇房不存在"));
    }
}
