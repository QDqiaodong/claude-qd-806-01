package com.fungus.farm.dto;

/** 撤回一次尚未开始出菇的入房分配；operator 留痕到 room_entry.withdrawn_by */
public record RoomEntryWithdrawRequest(String operator) {
}
