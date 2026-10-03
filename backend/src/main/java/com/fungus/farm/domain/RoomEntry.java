package com.fungus.farm.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 分批入房记录：已开袋菌包按数量拆次投产时生成。
 * 在制记录与出菇批次 1:1；撤回后出菇批次删除、数量归还，本记录以“已撤回”留痕。
 * 没有对应记录的 fruiting_batch 视为历史数据，不强制补造。
 */
@Data
public class RoomEntry {
    private Long id;
    private String entryNo;
    private Long bagBatchId;
    private Long roomId;
    private Long fruitingBatchId;
    private Integer quantity;
    private LocalDate plannedEntryDate;
    private String manager;
    private String status;
    private String withdrawnBy;
    private LocalDateTime withdrawnAt;
    private String fruitingBatchNo;
    private LocalDateTime createdAt;
}
