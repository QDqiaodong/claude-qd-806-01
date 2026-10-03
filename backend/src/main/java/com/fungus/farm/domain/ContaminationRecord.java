package com.fungus.farm.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ContaminationRecord {
    private Long id;
    private Long bagBatchId;
    private String contaminationType;
    private LocalDate discoveredDate;
    private String treatment;
    private Integer lossQuantity;
    private String note;
    private LocalDateTime createdAt;
}
