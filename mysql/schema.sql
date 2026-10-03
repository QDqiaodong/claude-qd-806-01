SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS harvest_inventory;
DROP TABLE IF EXISTS contamination_record;
DROP TABLE IF EXISTS harvest_flush;
DROP TABLE IF EXISTS environment_ticket_event;
DROP TABLE IF EXISTS environment_ticket_batch;
DROP TABLE IF EXISTS environment_ticket;
DROP TABLE IF EXISTS room_env_state;
DROP TABLE IF EXISTS room_entry;
DROP TABLE IF EXISTS fruiting_batch;
DROP TABLE IF EXISTS environment_record;
DROP TABLE IF EXISTS rack_slot;
DROP TABLE IF EXISTS bag_batch;
DROP TABLE IF EXISTS rack;
DROP TABLE IF EXISTS grow_room;
SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE grow_room (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_code VARCHAR(32) NOT NULL UNIQUE,
    orientation VARCHAR(16) NOT NULL,
    area DECIMAL(8,2) NOT NULL,
    species VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rack (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_id BIGINT NOT NULL,
    rack_code VARCHAR(32) NOT NULL UNIQUE,
    levels INT NOT NULL,
    slots_per_level INT NOT NULL,
    CONSTRAINT fk_rack_room FOREIGN KEY (room_id) REFERENCES grow_room(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE bag_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_no VARCHAR(40) NOT NULL UNIQUE,
    formula VARCHAR(120) NOT NULL,
    inoculation_date DATE NOT NULL,
    full_date DATE NULL,
    status VARCHAR(16) NOT NULL,
    total_quantity INT NOT NULL,
    available_quantity INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rack_slot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rack_id BIGINT NOT NULL,
    level_no INT NOT NULL,
    slot_no INT NOT NULL,
    bag_batch_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    shelved_at DATETIME NOT NULL,
    UNIQUE KEY uk_rack_position (rack_id, level_no, slot_no),
    CONSTRAINT fk_slot_rack FOREIGN KEY (rack_id) REFERENCES rack(id),
    CONSTRAINT fk_slot_bag FOREIGN KEY (bag_batch_id) REFERENCES bag_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE environment_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_id BIGINT NOT NULL,
    temperature DECIMAL(5,2) NOT NULL,
    humidity DECIMAL(5,2) NOT NULL,
    co2_ppm INT NOT NULL,
    light_lux INT NOT NULL,
    recorded_at DATETIME NOT NULL,
    over_limit TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_environment_room_time (room_id, recorded_at),
    KEY idx_environment_room_time (room_id, recorded_at),
    CONSTRAINT fk_environment_room FOREIGN KEY (room_id) REFERENCES grow_room(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 每个菇房一行的环境判定水位线：只有比 last_recorded_at 更新的读数才参与连续越限/恢复判断，
-- 迟到或乱序读数只存档、不推进状态机。recent_ids 记录最近推进水位线的读数 id，用于触发快照。
CREATE TABLE room_env_state (
    room_id BIGINT PRIMARY KEY,
    last_recorded_at DATETIME NULL,
    over_streak INT NOT NULL DEFAULT 0,
    recover_streak INT NOT NULL DEFAULT 0,
    recent_ids VARCHAR(160) NOT NULL DEFAULT '',
    CONSTRAINT fk_env_state_room FOREIGN KEY (room_id) REFERENCES grow_room(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 环境越限处置单：同一菇房最多一张未解除单（active_room_id 生成列 + 唯一索引兜底）
CREATE TABLE environment_ticket (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    trigger_metric VARCHAR(255) NOT NULL,
    trigger_readings TEXT NOT NULL,
    trigger_at DATETIME NOT NULL,
    over_count INT NOT NULL DEFAULT 3,
    recover_count INT NOT NULL DEFAULT 0,
    last_recorded_at DATETIME NOT NULL,
    claimed_by VARCHAR(40) NULL,
    claimed_at DATETIME NULL,
    expected_finish_at DATETIME NULL,
    resolved_by VARCHAR(40) NULL,
    resolved_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    active_room_id BIGINT GENERATED ALWAYS AS (IF(status = '已解除', NULL, room_id)) STORED,
    UNIQUE KEY uk_ticket_active_room (active_room_id),
    KEY idx_ticket_room (room_id, status),
    CONSTRAINT fk_ticket_room FOREIGN KEY (room_id) REFERENCES grow_room(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 处置单时间线：触发、接单、处理记录、恶化、恢复待确认、反复、解除
CREATE TABLE environment_ticket_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    actor VARCHAR(40) NULL,
    detail VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_event_ticket (ticket_id, id),
    CONSTRAINT fk_event_ticket FOREIGN KEY (ticket_id) REFERENCES environment_ticket(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE fruiting_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_no VARCHAR(40) NOT NULL UNIQUE,
    room_id BIGINT NOT NULL,
    bag_batch_id BIGINT NOT NULL,
    species VARCHAR(16) NOT NULL,
    entry_date DATE NOT NULL,
    expected_yield DECIMAL(10,2) NOT NULL,
    harvested_yield DECIMAL(10,2) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT fk_fruiting_room FOREIGN KEY (room_id) REFERENCES grow_room(id),
    CONSTRAINT fk_fruiting_bag FOREIGN KEY (bag_batch_id) REFERENCES bag_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 分批入房记录：已开袋菌包按数量拆次投产，每条在制记录与一个出菇批次一一对应。
-- 撤回入房时出菇批次被删除、数量归还，记录保留为“已撤回”审计轨迹，因此
-- fruiting_batch_id 允许为空，并以非空部分唯一索引兜底，防止重复配对/重复批次。
-- 历史已有的出菇批次不强制补造记录（fruiting_batch 无对应 room_entry 即视为历史数据）。
CREATE TABLE room_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    entry_no VARCHAR(40) NOT NULL UNIQUE,
    bag_batch_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    fruiting_batch_id BIGINT NULL,
    quantity INT NOT NULL,
    planned_entry_date DATE NOT NULL,
    manager VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT '在制',
    withdrawn_by VARCHAR(40) NOT NULL DEFAULT '',
    withdrawn_at DATETIME NULL,
    fruiting_batch_no VARCHAR(40) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_entry_active_fruiting (fruiting_batch_id),
    KEY idx_entry_bag (bag_batch_id, status),
    CONSTRAINT fk_entry_bag FOREIGN KEY (bag_batch_id) REFERENCES bag_batch(id),
    CONSTRAINT fk_entry_room FOREIGN KEY (room_id) REFERENCES grow_room(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE harvest_flush (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    flush_no INT NOT NULL,
    harvest_date DATE NULL,
    grade_a_yield DECIMAL(10,2) NOT NULL DEFAULT 0,
    grade_b_yield DECIMAL(10,2) NOT NULL DEFAULT 0,
    grade_c_yield DECIMAL(10,2) NOT NULL DEFAULT 0,
    yield_kg DECIMAL(10,2) NOT NULL DEFAULT 0,
    harvested_by VARCHAR(40) NULL,
    status VARCHAR(16) NOT NULL,
    UNIQUE KEY uk_batch_flush (batch_id, flush_no),
    CONSTRAINT fk_flush_batch FOREIGN KEY (batch_id) REFERENCES fruiting_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 处置单暂停的出菇批次快照：解除时只恢复本单暂停且仍处于环境暂停的批次
CREATE TABLE environment_ticket_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    fruiting_batch_id BIGINT NOT NULL,
    prev_status VARCHAR(16) NOT NULL,
    restored TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ticket_batch (ticket_id, fruiting_batch_id),
    CONSTRAINT fk_ticket_batch_ticket FOREIGN KEY (ticket_id) REFERENCES environment_ticket(id),
    CONSTRAINT fk_ticket_batch_batch FOREIGN KEY (fruiting_batch_id) REFERENCES fruiting_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE contamination_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    bag_batch_id BIGINT NOT NULL,
    contamination_type VARCHAR(16) NOT NULL,
    discovered_date DATE NOT NULL,
    treatment VARCHAR(16) NOT NULL,
    loss_quantity INT NOT NULL,
    note VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_contamination_bag FOREIGN KEY (bag_batch_id) REFERENCES bag_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE harvest_inventory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    fruiting_batch_id BIGINT NOT NULL,
    flush_no INT NOT NULL,
    quantity DECIMAL(10,2) NOT NULL,
    grade_summary VARCHAR(160) NOT NULL,
    operator VARCHAR(40) NOT NULL,
    recorded_at DATETIME NOT NULL,
    CONSTRAINT fk_inventory_batch FOREIGN KEY (fruiting_batch_id) REFERENCES fruiting_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO grow_room (room_code, orientation, area, species, status) VALUES
('GR-A01', '东向', 128.50, '金针菇', '出菇'),
('GR-A02', '南向', 136.00, '杏鲍菇', '出菇'),
('GR-B01', '西向', 118.80, '香菇', '养菌'),
('GR-B02', '北向', 142.60, '平菇', '采收'),
('GR-C01', '东南', 96.40, '金针菇', '休整');

INSERT INTO rack (room_id, rack_code, levels, slots_per_level) VALUES
(1, 'A01-R1', 5, 8),
(1, 'A01-R2', 4, 8),
(2, 'A02-R1', 5, 6),
(3, 'B01-R1', 4, 8),
(4, 'B02-R1', 6, 6),
(5, 'C01-R1', 4, 6);

INSERT INTO bag_batch (batch_no, formula, inoculation_date, full_date, status, total_quantity, available_quantity) VALUES
('BG-260801-JZ', '棉籽壳55%+麸皮25%+木屑20%', '2026-08-01', '2026-08-23', '已开袋', 1200, 240),
('BG-260805-XB', '玉米芯45%+木屑35%+麸皮20%', '2026-08-05', '2026-08-29', '已开袋', 980, 180),
('BG-260812-XG', '阔叶木屑78%+麸皮20%+石膏2%', '2026-08-12', '2026-09-08', '满袋', 860, 300),
('BG-260816-PG', '棉籽壳60%+玉米芯25%+麸皮15%', '2026-08-16', '2026-09-05', '污染', 1050, 420),
('BG-260820-JZ', '棉籽壳50%+玉米芯30%+米糠20%', '2026-08-20', '2026-09-12', '满袋', 1120, 640),
('BG-260825-XB', '木屑50%+玉米芯30%+麸皮20%', '2026-08-25', NULL, '养菌', 900, 900),
('BG-260828-XG', '栎木屑80%+麸皮18%+石膏2%', '2026-08-28', NULL, '养菌', 780, 780),
('BG-260901-PG', '棉籽壳65%+麸皮20%+玉米芯15%', '2026-09-01', '2026-09-20', '报废', 760, 0),
('BG-260908-JZ', '棉籽壳58%+麸皮22%+木屑20%', '2026-09-08', '2026-09-25', '污染', 840, 840),
('BG-260912-XB', '玉米芯50%+木屑30%+麸皮20%', '2026-09-12', NULL, '养菌', 920, 920);

INSERT INTO rack_slot (rack_id, level_no, slot_no, bag_batch_id, quantity, shelved_at) VALUES
(1, 5, 1, 1, 80, '2026-09-01 08:20:00'),
(1, 5, 2, 1, 80, '2026-09-01 08:25:00'),
(1, 4, 1, 5, 80, '2026-09-13 09:10:00'),
(2, 4, 1, 1, 80, '2026-09-01 09:00:00'),
(2, 3, 2, 5, 80, '2026-09-13 09:20:00'),
(3, 5, 1, 2, 100, '2026-09-03 08:40:00'),
(3, 4, 2, 2, 100, '2026-09-03 08:45:00'),
(4, 4, 1, 3, 70, '2026-09-10 10:00:00'),
(4, 3, 3, 3, 70, '2026-09-10 10:10:00'),
(5, 6, 1, 4, 90, '2026-09-07 11:00:00'),
(5, 5, 2, 4, 90, '2026-09-07 11:05:00'),
(6, 4, 1, 7, 60, '2026-09-18 14:00:00');

INSERT INTO environment_record (room_id, temperature, humidity, co2_ppm, light_lux, recorded_at, over_limit)
SELECT r.id,
       CASE
         WHEN r.id=2 AND h.n>=21 THEN 20.2
         WHEN r.id=1 THEN 8.0 + MOD(h.n,4)*0.3
         WHEN r.id=2 THEN 15.0 + MOD(h.n,5)*0.2
         WHEN r.id=3 THEN 16.0 + MOD(h.n,3)*0.4
         WHEN r.id=4 THEN 18.0 + MOD(h.n,4)*0.5
         ELSE 9.0 + MOD(h.n,3)*0.3
       END,
       CASE WHEN r.id=3 AND h.n>=21 THEN 77.5 ELSE 86 + MOD(h.n,7) END,
       CASE WHEN r.id=4 AND h.n>=21 THEN 2180 ELSE 760 + r.id*90 + MOD(h.n,6)*35 END,
       90 + r.id*25 + MOD(h.n,8)*12,
       TIMESTAMP('2026-09-26 08:00:00') + INTERVAL h.n HOUR,
       CASE WHEN (r.id=2 OR r.id=3 OR r.id=4) AND h.n>=21 THEN 1 ELSE 0 END
FROM grow_room r
CROSS JOIN (
    SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL
    SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL
    SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL
    SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15 UNION ALL
    SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19 UNION ALL
    SELECT 20 UNION ALL SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23
) h;

INSERT INTO fruiting_batch (batch_no, room_id, bag_batch_id, species, entry_date, expected_yield, harvested_yield, status) VALUES
('FB-260901-01', 1, 1, '金针菇', '2026-09-01', 520.00, 294.00, '二潮结束'),
('FB-260905-02', 2, 2, '杏鲍菇', '2026-09-05', 460.00, 242.00, '二潮结束'),
('FB-260910-03', 3, 3, '香菇', '2026-09-10', 610.00, 210.00, '一潮结束'),
('FB-260912-04', 4, 4, '平菇', '2026-09-12', 390.00, 216.00, '二潮结束'),
('FB-260915-05', 1, 5, '金针菇', '2026-09-15', 500.00, 0.00, '待采收');

INSERT INTO harvest_flush (batch_id, flush_no, harvest_date, grade_a_yield, grade_b_yield, grade_c_yield, yield_kg, harvested_by, status) VALUES
(1, 1, '2026-09-08', 112.00, 42.00, 14.00, 168.00, '周启明', '已采收'),
(1, 2, '2026-09-18', 78.00, 36.00, 12.00, 126.00, '周启明', '已采收'),
(1, 3, NULL, 0, 0, 0, 0, NULL, '待采收'),
(2, 1, '2026-09-12', 88.00, 42.00, 14.00, 144.00, '许芳', '已采收'),
(2, 2, '2026-09-22', 58.00, 31.00, 9.00, 98.00, '许芳', '已采收'),
(2, 3, NULL, 0, 0, 0, 0, NULL, '待采收'),
(3, 1, '2026-09-20', 126.00, 63.00, 21.00, 210.00, '韩冬', '已采收'),
(3, 2, NULL, 0, 0, 0, 0, NULL, '待采收'),
(3, 3, NULL, 0, 0, 0, 0, NULL, '待采收'),
(4, 1, '2026-09-18', 76.00, 42.00, 14.00, 132.00, '林洁', '已采收'),
(4, 2, '2026-09-25', 46.00, 29.00, 9.00, 84.00, '林洁', '已采收'),
(4, 3, NULL, 0, 0, 0, 0, NULL, '待采收'),
(5, 1, NULL, 0, 0, 0, 0, NULL, '待采收'),
(5, 2, NULL, 0, 0, 0, 0, NULL, '待采收'),
(5, 3, NULL, 0, 0, 0, 0, NULL, '待采收');

INSERT INTO contamination_record (bag_batch_id, contamination_type, discovered_date, treatment, loss_quantity, note) VALUES
(4, '绿霉', '2026-09-19', '隔离', 36, '集中在下层靠门侧'),
(4, '细菌', '2026-09-21', '药剂', 18, '袋口黏液及时消杀'),
(8, '链孢霉', '2026-09-16', '剔除', 54, '整格移出菇房'),
(8, '绿霉', '2026-09-17', '剔除', 42, '相邻菌包同步报废'),
(4, '链孢霉', '2026-09-23', '隔离', 22, '单独转入观察区'),
(8, '细菌', '2026-09-24', '药剂', 16, '地面与层架完成消毒');

INSERT INTO harvest_inventory (fruiting_batch_id, flush_no, quantity, grade_summary, operator, recorded_at) VALUES
(1, 1, 168.00, 'A:112kg / B:42kg / C:14kg', '周启明', '2026-09-08 17:10:00'),
(1, 2, 126.00, 'A:78kg / B:36kg / C:12kg', '周启明', '2026-09-18 16:40:00'),
(2, 1, 144.00, 'A:88kg / B:42kg / C:14kg', '许芳', '2026-09-12 18:05:00'),
(2, 2, 98.00, 'A:58kg / B:31kg / C:9kg', '许芳', '2026-09-22 16:25:00'),
(3, 1, 210.00, 'A:126kg / B:63kg / C:21kg', '韩冬', '2026-09-20 18:30:00'),
(4, 1, 132.00, 'A:76kg / B:42kg / C:14kg', '林洁', '2026-09-18 17:50:00'),
(4, 2, 84.00, 'A:46kg / B:29kg / C:9kg', '林洁', '2026-09-25 15:35:00');
