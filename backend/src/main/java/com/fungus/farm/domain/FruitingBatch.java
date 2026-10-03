package com.fungus.farm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

@Data
public class FruitingBatch {
    private Long id;
    private String batchNo;
    private Long roomId;
    private Long bagBatchId;
    private String species;
    private LocalDate entryDate;
    private BigDecimal expectedYield;
    private BigDecimal harvestedYield;
    private String status;
}
