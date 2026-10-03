package com.fungus.farm.common;

import com.fungus.farm.domain.EnvironmentRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 各菇种环境适宜区间与越限判定口径。
 * 读数越限标记、催蕾校验、处置单触发指标快照统一从这里取，避免多处口径漂移。
 */
public final class EnvironmentLimits {

    private EnvironmentLimits() {
    }

    /** 返回 [最低温度, 最高温度, 最低湿度, 最高湿度, CO₂ 上限] */
    public static BigDecimal[] range(String species) {
        return switch (species) {
            case "金针菇" -> new BigDecimal[] {BigDecimal.valueOf(4), BigDecimal.valueOf(12), BigDecimal.valueOf(85), BigDecimal.valueOf(95), BigDecimal.valueOf(1200)};
            case "杏鲍菇" -> new BigDecimal[] {BigDecimal.valueOf(12), BigDecimal.valueOf(18), BigDecimal.valueOf(85), BigDecimal.valueOf(95), BigDecimal.valueOf(1500)};
            case "香菇" -> new BigDecimal[] {BigDecimal.valueOf(12), BigDecimal.valueOf(20), BigDecimal.valueOf(80), BigDecimal.valueOf(95), BigDecimal.valueOf(1500)};
            default -> new BigDecimal[] {BigDecimal.valueOf(15), BigDecimal.valueOf(22), BigDecimal.valueOf(80), BigDecimal.valueOf(95), BigDecimal.valueOf(1800)};
        };
    }

    public static boolean isOverLimit(String species, EnvironmentRecord record) {
        BigDecimal[] range = range(species);
        return record.getTemperature().compareTo(range[0]) < 0
                || record.getTemperature().compareTo(range[1]) > 0
                || record.getHumidity().compareTo(range[2]) < 0
                || record.getHumidity().compareTo(range[3]) > 0
                || BigDecimal.valueOf(record.getCo2Ppm()).compareTo(range[4]) > 0;
    }

    /** 越限指标的中文描述，如「温度 20.2℃（适宜 12~18℃）」，全部适宜时返回空列表 */
    public static List<String> violations(String species, EnvironmentRecord record) {
        BigDecimal[] range = range(species);
        List<String> errors = new ArrayList<>();
        if (record.getTemperature().compareTo(range[0]) < 0 || record.getTemperature().compareTo(range[1]) > 0) {
            errors.add("温度 " + record.getTemperature() + "℃（适宜 " + range[0] + "~" + range[1] + "℃）");
        }
        if (record.getHumidity().compareTo(range[2]) < 0 || record.getHumidity().compareTo(range[3]) > 0) {
            errors.add("湿度 " + record.getHumidity() + "%（适宜 " + range[2] + "~" + range[3] + "%）");
        }
        if (BigDecimal.valueOf(record.getCo2Ppm()).compareTo(range[4]) > 0) {
            errors.add("CO₂ " + record.getCo2Ppm() + "ppm（上限 " + range[4] + "ppm）");
        }
        return errors;
    }
}
