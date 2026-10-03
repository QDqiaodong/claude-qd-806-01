package com.fungus.farm.service;

import com.fungus.farm.common.BizException;
import com.fungus.farm.common.EnvironmentLimits;
import com.fungus.farm.domain.BagBatch;
import com.fungus.farm.domain.EnvironmentRecord;
import com.fungus.farm.domain.GrowRoom;
import com.fungus.farm.domain.Rack;
import com.fungus.farm.domain.RackSlot;
import com.fungus.farm.dto.ShelfRequest;
import com.fungus.farm.repository.BagRepository;
import com.fungus.farm.repository.EnvironmentRepository;
import com.fungus.farm.repository.GrowRoomRepository;
import com.fungus.farm.repository.RackRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {
    private static final Set<String> STATUSES = Set.of("养菌", "催蕾", "出菇", "采收", "休整");

    private final GrowRoomRepository roomRepository;
    private final RackRepository rackRepository;
    private final BagRepository bagRepository;
    private final EnvironmentRepository environmentRepository;

    public RoomService(GrowRoomRepository roomRepository, RackRepository rackRepository,
            BagRepository bagRepository, EnvironmentRepository environmentRepository) {
        this.roomRepository = roomRepository;
        this.rackRepository = rackRepository;
        this.bagRepository = bagRepository;
        this.environmentRepository = environmentRepository;
    }

    public List<Map<String, Object>> rooms() {
        return roomRepository.findOverview();
    }

    public Map<String, Integer> overview() {
        return Map.of("roomCount", roomRepository.countRooms(), "batchCount", roomRepository.countFruitingBatches());
    }

    public List<Map<String, Object>> racks(Long roomId) {
        room(roomId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Rack rack : rackRepository.findByRoomId(roomId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rack.getId());
            item.put("roomId", rack.getRoomId());
            item.put("rackCode", rack.getRackCode());
            item.put("levels", rack.getLevels());
            item.put("slotsPerLevel", rack.getSlotsPerLevel());
            item.put("slots", rackRepository.findSlots(rack.getId()));
            result.add(item);
        }
        return result;
    }

    @Transactional
    public void changeStatus(Long roomId, String status) {
        GrowRoom room = room(roomId);
        if (!STATUSES.contains(status)) {
            throw new BizException("菇房状态必须为养菌、催蕾、出菇、采收或休整");
        }
        if ("催蕾".equals(status)) {
            EnvironmentRecord record = environmentRepository.findLatestByRoomId(roomId)
                    .orElseThrow(() -> new BizException("菇房暂无环境记录，不能进入催蕾阶段"));
            List<String> errors = environmentErrors(room.getSpecies(), record);
            if (!errors.isEmpty()) {
                throw new BizException("环境参数超标，不能进入催蕾阶段：" + String.join("；", errors));
            }
        }
        roomRepository.updateStatus(roomId, status);
    }

    @Transactional
    public Long shelf(Long rackId, ShelfRequest request) {
        Rack rack = rackRepository.findById(rackId).orElseThrow(() -> new BizException("培养架不存在"));
        if (request.levelNo() == null || request.slotNo() == null || request.quantity() == null || request.quantity() <= 0) {
            throw new BizException("层号、格位号和上架数量必须为正数");
        }
        if (request.levelNo() > rack.getLevels() || request.slotNo() > rack.getSlotsPerLevel()) {
            throw new BizException("所选位置超出培养架层数或格位范围");
        }
        if (rackRepository.isOccupied(rackId, request.levelNo(), request.slotNo())) {
            throw new BizException("该格位已有菌包，请选择空格位");
        }
        BagBatch batch = bagRepository.findById(request.bagBatchId()).orElseThrow(() -> new BizException("菌包批次不存在"));
        if ("污染".equals(batch.getStatus()) || "报废".equals(batch.getStatus())) {
            throw new BizException("污染或报废菌包不能上架");
        }
        if (batch.getAvailableQuantity() == null || batch.getAvailableQuantity() < request.quantity()) {
            throw new BizException("可上架菌包不足，当前可用 " + batch.getAvailableQuantity() + " 包");
        }
        RackSlot slot = new RackSlot();
        slot.setRackId(rackId);
        slot.setLevelNo(request.levelNo());
        slot.setSlotNo(request.slotNo());
        slot.setBagBatchId(request.bagBatchId());
        slot.setQuantity(request.quantity());
        slot.setShelvedAt(LocalDateTime.now());
        Long id = rackRepository.saveSlot(slot);
        if (bagRepository.decreaseAvailable(batch.getId(), request.quantity()) == 0) {
            throw new BizException("菌包数量已变化，请刷新后重试");
        }
        return id;
    }

    private GrowRoom room(Long id) {
        return roomRepository.findById(id).orElseThrow(() -> new BizException("菇房不存在"));
    }

    private List<String> environmentErrors(String species, EnvironmentRecord record) {
        return EnvironmentLimits.violations(species, record);
    }
}
