package com.fungus.farm.domain;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 环境越限处置单。状态机：待接单 → 处理中 → 恢复待确认 → 已解除；
 * 恢复待确认期间再次越限会退回处理中（未接单则退回待接单）。
 */
@Data
public class EnvironmentTicket {
    private Long id;
    private Long roomId;
    private String status;
    private String triggerMetric;
    private String triggerReadings;
    private LocalDateTime triggerAt;
    private Integer overCount;
    private Integer recoverCount;
    private LocalDateTime lastRecordedAt;
    private String claimedBy;
    private LocalDateTime claimedAt;
    private LocalDateTime expectedFinishAt;
    private String resolvedBy;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
