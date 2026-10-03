package com.fungus.farm.domain;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 菇房环境判定水位线（每房一行）。只有比 lastRecordedAt 更新的读数才推进连续越限/恢复计数，
 * 迟到或乱序读数只存档；recentIds 记录最近推进水位线的读数 id，用于触发快照。
 */
@Data
public class RoomEnvState {
    private Long roomId;
    private LocalDateTime lastRecordedAt;
    private Integer overStreak;
    private Integer recoverStreak;
    private String recentIds;
}
