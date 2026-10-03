package com.fungus.farm.dto;

import java.time.LocalDateTime;

public record TicketClaimRequest(String operator, LocalDateTime expectedFinishAt) {
}
