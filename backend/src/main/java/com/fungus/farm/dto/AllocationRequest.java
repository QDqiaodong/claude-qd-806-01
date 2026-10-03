package com.fungus.farm.dto;

import java.time.LocalDate;

public record AllocationRequest(
        String requestKey,
        Long bagBatchId,
        Long roomId,
        Integer quantity,
        LocalDate plannedEntryDate,
        String manager) {
}
