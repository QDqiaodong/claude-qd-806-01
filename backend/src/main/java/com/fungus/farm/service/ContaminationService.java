package com.fungus.farm.service;

import com.fungus.farm.common.BizException;
import com.fungus.farm.domain.BagBatch;
import com.fungus.farm.domain.ContaminationRecord;
import com.fungus.farm.dto.ContaminationRequest;
import com.fungus.farm.repository.BagRepository;
import com.fungus.farm.repository.ContaminationRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContaminationService {
    private static final Set<String> TYPES = Set.of("绿霉", "链孢霉", "细菌");
    private static final Set<String> TREATMENTS = Set.of("剔除", "隔离", "药剂");

    private final ContaminationRepository repository;
    private final BagRepository bagRepository;

    public ContaminationService(ContaminationRepository repository, BagRepository bagRepository) {
        this.repository = repository;
        this.bagRepository = bagRepository;
    }

    public List<Map<String, Object>> list() {
        return repository.findAll();
    }

    public List<Map<String, Object>> summary() {
        return repository.summary();
    }

    @Transactional
    public Long register(ContaminationRequest request) {
        BagBatch batch = bagRepository.findById(request.bagBatchId()).orElseThrow(() -> new BizException("菌包批次不存在"));
        if (!TYPES.contains(request.contaminationType())) {
            throw new BizException("污染类型必须为绿霉、链孢霉或细菌");
        }
        if (!TREATMENTS.contains(request.treatment())) {
            throw new BizException("必须登记处理措施：剔除、隔离或药剂");
        }
        if (request.lossQuantity() == null || request.lossQuantity() <= 0 || request.lossQuantity() > batch.getTotalQuantity()) {
            throw new BizException("损失数量必须大于 0 且不能超过该批菌包总数");
        }
        ContaminationRecord record = new ContaminationRecord();
        record.setBagBatchId(request.bagBatchId());
        record.setContaminationType(request.contaminationType());
        record.setDiscoveredDate(request.discoveredDate() == null ? LocalDate.now() : request.discoveredDate());
        record.setTreatment(request.treatment());
        record.setLossQuantity(request.lossQuantity());
        record.setNote(request.note());
        record.setCreatedAt(LocalDateTime.now());
        Long id = repository.save(record);
        bagRepository.updateStatus(batch.getId(), "污染");
        return id;
    }

    @Transactional
    public void discard(Long bagBatchId) {
        BagBatch batch = bagRepository.findById(bagBatchId).orElseThrow(() -> new BizException("菌包批次不存在"));
        if (!"污染".equals(batch.getStatus())) {
            throw new BizException("只有已标记污染的菌包才能报废");
        }
        if (!repository.hasTreatment(bagBatchId)) {
            throw new BizException("污染菌包必须先登记处理措施才能报废");
        }
        bagRepository.updateStatus(bagBatchId, "报废");
    }
}
