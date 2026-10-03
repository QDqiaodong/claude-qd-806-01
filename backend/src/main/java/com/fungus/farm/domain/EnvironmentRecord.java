package com.fungus.farm.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class EnvironmentRecord {
    private Long id;
    private Long roomId;
    private BigDecimal temperature;
    private BigDecimal humidity;
    private Integer co2Ppm;
    private Integer lightLux;
    private LocalDateTime recordedAt;
    private Boolean overLimit;
}
