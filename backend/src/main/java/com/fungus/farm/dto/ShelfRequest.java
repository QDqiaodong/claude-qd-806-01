package com.fungus.farm.dto;

public record ShelfRequest(Long bagBatchId, Integer levelNo, Integer slotNo, Integer quantity) {
}
