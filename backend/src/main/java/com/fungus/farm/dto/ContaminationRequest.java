package com.fungus.farm.dto;

import java.time.LocalDate;

public record ContaminationRequest(
        Long bagBatchId,
        String contaminationType,
        LocalDate discoveredDate,
        String treatment,
        Integer lossQuantity,
        String note) {
}
