package com.fungus.farm.service;

import com.fungus.farm.common.BizException;
import com.fungus.farm.domain.BagBatch;
import com.fungus.farm.repository.BagRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BagService {
    private final BagRepository repository;

    public BagService(BagRepository repository) {
        this.repository = repository;
    }

    public List<BagBatch> list(String status) {
        return repository.findAll(status);
    }

    public List<Map<String, Object>> summary() {
        return repository.statusSummary();
    }

    @Transactional
    public void open(Long id) {
        BagBatch batch = repository.findById(id).orElseThrow(() -> new BizException("菌包批次不存在"));
        if (!"满袋".equals(batch.getStatus())) {
            throw new BizException("菌包未达到“满袋”状态，不允许开袋；当前状态：" + batch.getStatus());
        }
        repository.updateStatus(id, "已开袋");
    }
}
