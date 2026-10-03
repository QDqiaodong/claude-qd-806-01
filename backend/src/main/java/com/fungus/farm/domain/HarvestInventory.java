package com.fungus.farm.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class HarvestInventory {
    private Long id;
    private Long fruitingBatchId;
    private Integer flushNo;
    private BigDecimal quantity;
    private String gradeSummary;
    private String operator;
    private LocalDateTime recordedAt;
}
