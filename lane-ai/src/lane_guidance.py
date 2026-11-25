"""Offline lane guidance using an estimate and route-compatible lane IDs."""

from dataclasses import dataclass
from typing import Literal

from .contracts import LaneEstimate


@dataclass(frozen=True)
class LaneGuidance:
    direction: Literal["LEFT", "RIGHT", "KEEP", "UNKNOWN"]
    lane_changes: int | None
    target_lane_id: str | None


def plan_lane_guidance(
    estimate: LaneEstimate,
    road_segment_id: str,
    lanes_left_to_right: tuple[str, ...],
    route_lane_ids: set[str],
) -> LaneGuidance:
    """Use physical lane order for one approach and abstain on ambiguous ties.

    The caller supplies verified lane ordering and route-compatible IDs.
    """
    lane_id = estimate.lane_id if estimate.status == "VALID" and estimate.road_segment_id == road_segment_id else None
    return guidance_from_lane(lane_id, lanes_left_to_right, route_lane_ids)


def guidance_from_lane(
    current_lane_id: str | None,
    lanes_left_to_right: tuple[str, ...],
    route_lane_ids: set[str],
) -> LaneGuidance:
    """Calculate the movement from an already selected approach lane."""
    if len(set(lanes_left_to_right)) != len(lanes_left_to_right):
        raise ValueError("Each approach lane must appear once")

    unknown = LaneGuidance("UNKNOWN", None, None)
    if current_lane_id not in lanes_left_to_right:
        return unknown

    current = lanes_left_to_right.index(current_lane_id)
    deltas = [
        (index - current, lane_id)
        for index, lane_id in enumerate(lanes_left_to_right)
        if lane_id in route_lane_ids
    ]
    if not deltas:
        return unknown

    shortest = min(abs(delta) for delta, _ in deltas)
    closest = [(delta, lane_id) for delta, lane_id in deltas if abs(delta) == shortest]
    if len(closest) != 1:
        return unknown

    delta, lane_id = closest[0]
    if delta == 0:
        return LaneGuidance("KEEP", 0, lane_id)
    return LaneGuidance("LEFT" if delta < 0 else "RIGHT", abs(delta), lane_id)
