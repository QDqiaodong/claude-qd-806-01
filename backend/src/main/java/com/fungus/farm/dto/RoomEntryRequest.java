package com.fungus.farm.dto;

import java.time.LocalDate;

/**
 * 一次分批入房分配：选择菇房、入房数量、计划入房日与负责人。
 * 数量可等于剩余可用量（整批投产），也可只取一部分（拆次入房）。
 */
public record RoomEntryRequest(
        Long roomId,
        Integer quantity,
        LocalDate plannedEntryDate,
        String manager) {
}
