package com.example.naviserver.dto;

import java.util.List;

public record NavResponse(
        boolean ai_trigger,
        String maneuver,
        Integer total_lanes,
        List<Integer> required_lanes,
        Integer distance_to_maneuver
) {}
