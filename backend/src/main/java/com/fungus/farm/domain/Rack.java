package com.fungus.farm.domain;

import lombok.Data;

@Data
public class Rack {
    private Long id;
    private Long roomId;
    private String rackCode;
    private Integer levels;
    private Integer slotsPerLevel;
}
