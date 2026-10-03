package com.fungus.farm.service;

import com.fungus.farm.common.BizException;
import com.fungus.farm.domain.FruitingBatch;
import com.fungus.farm.domain.HarvestFlush;
import com.fungus.farm.domain.HarvestInventory;
import com.fungus.farm.dto.HarvestRequest;
import com.fungus.farm.repository.FruitingRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FruitingService {
    private final FruitingRepository repository;

    public FruitingService(FruitingRepository repository) {
        this.repository = repository;
    }

    public List<Map<String, Object>> batches() {
        return repository.findAllWithHarvests();
    }

    public List<HarvestFlush> flushes(Long batchId) {
        batch(batchId);
        return repository.findFlushes(batchId);
    }

    public List<Map<String, Object>> roomYieldStats() {
        return repository.yieldStatsByRoom();
    }

    @Transactional
    public HarvestFlush harvest(Long batchId, Integer flushNo, HarvestRequest request) {
        if (flushNo == null || flushNo < 1 || flushNo > 3) {
            throw new BizException("潮次只能是一潮、二潮或三潮");
        }
        FruitingBatch batch = batch(batchId);
        if ("环境暂停".equals(batch.getStatus())) {
            throw new BizException("该批次因环境越限处置暂停生产，处置单解除后才能采收");
        }
        BigDecimal gradeA = zero(request.gradeAYield());
        BigDecimal gradeB = zero(request.gradeBYield());
        BigDecimal gradeC = zero(request.gradeCYield());
        if (gradeA.signum() < 0 || gradeB.signum() < 0 || gradeC.signum() < 0) {
            throw new BizException("各等级采收量不得为负数");
        }
        BigDecimal total = gradeA.add(gradeB).add(gradeC);
        if (total.signum() <= 0) {
            throw new BizException("本潮采收总量必须大于 0 kg");
        }
        HarvestFlush flush = repository.findFlush(batchId, flushNo).orElseGet(() -> {
            HarvestFlush item = new HarvestFlush();
            item.setBatchId(batchId);
            item.setFlushNo(flushNo);
            item.setYieldKg(BigDecimal.ZERO);
            return item;
        });
        BigDecimal oldYield = zero(flush.getYieldKg());
        BigDecimal currentHarvested = zero(batch.getHarvestedYield());
        BigDecimal newHarvested = currentHarvested.subtract(oldYield).add(total);
        if (newHarvested.compareTo(batch.getExpectedYield()) > 0) {
            BigDecimal remaining = batch.getExpectedYield().subtract(currentHarvested).add(oldYield);
            throw new BizException("本潮采收量超过批次剩余预产量，当前最多可录入 " + remaining + " kg");
        }
        if (request.harvestedBy() == null || request.harvestedBy().isBlank()) {
            throw new BizException("采收人不能为空");
        }
        flush.setHarvestDate(request.harvestDate() == null ? LocalDate.now() : request.harvestDate());
        flush.setGradeAYield(gradeA);
        flush.setGradeBYield(gradeB);
        flush.setGradeCYield(gradeC);
        flush.setYieldKg(total);
        flush.setHarvestedBy(request.harvestedBy().trim());
        flush.setStatus("已采收");
        if (flush.getId() == null) {
            flush.setId(repository.saveFlush(flush));
        } else {
            repository.updateFlush(flush);
        }
        repository.updateBatchYield(batchId, newHarvested);

        HarvestInventory inventory = new HarvestInventory();
        inventory.setFruitingBatchId(batchId);
        inventory.setFlushNo(flushNo);
        inventory.setQuantity(total.subtract(oldYield));
        inventory.setGradeSummary("A:" + gradeA + "kg / B:" + gradeB + "kg / C:" + gradeC + "kg");
        inventory.setOperator(flush.getHarvestedBy());
        inventory.setRecordedAt(LocalDateTime.now());
        repository.saveInventory(inventory);
        return flush;
    }

    private FruitingBatch batch(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException("出菇批次不存在"));
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
