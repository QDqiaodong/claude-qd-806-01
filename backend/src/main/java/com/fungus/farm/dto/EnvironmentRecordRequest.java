package com.fungus.farm.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record EnvironmentRecordRequest(
        Long roomId,
        BigDecimal temperature,
        BigDecimal humidity,
        Integer co2Ppm,
        Integer lightLux,
        LocalDateTime recordedAt) {
}
