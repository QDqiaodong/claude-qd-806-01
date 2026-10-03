package com.fungus.farm.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class BagAllocation {
    private Long id;
    private String allocationNo;
    private String requestKey;
    private Long bagBatchId;
    private Long roomId;
    private Long fruitingBatchId;
    private String fruitingBatchNo;
    private Integer quantity;
    private LocalDate plannedEntryDate;
    private String manager;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime revokedAt;
    private String revokedBy;
    private String revokeNote;
}
