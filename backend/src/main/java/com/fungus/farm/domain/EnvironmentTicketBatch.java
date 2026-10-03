package com.fungus.farm.domain;

import lombok.Data;

/**
 * 处置单暂停的出菇批次快照：记录暂停前状态，解除时只恢复本单暂停且仍处于环境暂停的批次。
 */
@Data
public class EnvironmentTicketBatch {
    private Long id;
    private Long ticketId;
    private Long fruitingBatchId;
    private String prevStatus;
    private Boolean restored;
}
