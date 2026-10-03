package com.fungus.farm.domain;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 处置单时间线事件：触发 / 暂停批次 / 接单 / 处理记录 / 恶化 / 恢复待确认 / 反复 / 解除。
 */
@Data
public class EnvironmentTicketEvent {
    private Long id;
    private Long ticketId;
    private String eventType;
    private String actor;
    private String detail;
    private LocalDateTime createdAt;
}
