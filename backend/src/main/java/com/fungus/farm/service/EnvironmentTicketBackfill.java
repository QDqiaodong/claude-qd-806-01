package com.fungus.farm.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动时按采集时间重放存量环境读数，让已有的连续越限不再只停留在超标标记，
 * 而是补齐水位线并生成对应的越限处置单。水位线非空（已初始化过）时直接跳过。
 */
@Component
public class EnvironmentTicketBackfill implements ApplicationRunner {
    private final EnvironmentTicketService ticketService;

    public EnvironmentTicketBackfill(EnvironmentTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Override
    public void run(ApplicationArguments args) {
        ticketService.backfill();
    }
}
