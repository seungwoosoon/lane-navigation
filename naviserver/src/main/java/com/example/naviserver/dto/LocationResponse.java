package com.example.naviserver.dto;

import java.util.List;

public record LocationResponse(boolean trigger, int totalLanes, List<Integer> targetLanes, double distanceToBranch) {
}
