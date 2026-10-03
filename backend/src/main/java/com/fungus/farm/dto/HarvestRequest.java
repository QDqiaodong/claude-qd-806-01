package com.fungus.farm.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record HarvestRequest(
        LocalDate harvestDate,
        BigDecimal gradeAYield,
        BigDecimal gradeBYield,
        BigDecimal gradeCYield,
        String harvestedBy) {
}
