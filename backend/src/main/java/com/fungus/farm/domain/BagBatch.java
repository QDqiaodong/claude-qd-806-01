package com.fungus.farm.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class BagBatch {
    private Long id;
    private String batchNo;
    private String formula;
    private LocalDate inoculationDate;
    private LocalDate fullDate;
    private Long rackSlotId;
    private String status;
    private Integer totalQuantity;
    private Integer availableQuantity;
    private LocalDateTime createdAt;
}
