package com.fungus.farm.domain;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class RackSlot {
    private Long id;
    private Long rackId;
    private Integer levelNo;
    private Integer slotNo;
    private Long bagBatchId;
    private Integer quantity;
    private LocalDateTime shelvedAt;
}
