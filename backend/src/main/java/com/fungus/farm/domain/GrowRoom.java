package com.fungus.farm.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class GrowRoom {
    private Long id;
    private String roomCode;
    private String orientation;
    private BigDecimal area;
    private String species;
    private String status;
    private LocalDateTime createdAt;
}
