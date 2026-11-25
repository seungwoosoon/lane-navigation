"""Track a lane through a recorded approach using map links and camera events."""

from dataclasses import dataclass
from typing import Literal

from .lane_guidance import guidance_from_lane


Crossing = Literal["LEFT", "RIGHT"]


@dataclass(frozen=True)
class LaneSnapshot:
    gps_second: float
    lanes_left_to_right: tuple[str, ...]
    gps_lane_id: str | None
    route_lane_ids: frozenset[str]
    crossing: Crossing | None = None
    gps_observation: bool = True


@dataclass(frozen=True)
class TrackedGuidance:
    gps_second: float
    map_lane_id: str | None
    display_lane: int | None
    target_display_lane: int | None
    direction: Literal["PREPARE", "LEFT", "RIGHT", "KEEP", "UNKNOWN"]
    lane_changes: int | None
    evidence: str


def _continue_lane(
    previous: str, lanes: tuple[str, ...], successors: dict[str, tuple[str, ...]],
    map_lane_numbers: dict[str, str],
) -> str | None:
    if previous in lanes:
        return previous
    forward = [lane for lane in successors.get(previous, ()) if lane in lanes]
    if len(forward) == 1:
        return forward[0]
    same_number = [lane for lane in forward if map_lane_numbers[lane] == map_lane_numbers[previous]]
    return same_number[0] if len(same_number) == 1 else None


def replay_lane_tracking(
    snapshots: list[LaneSnapshot],
    successors: dict[str, tuple[str, ...]],
    map_lane_numbers: dict[str, str],
    initial_votes_required: int = 4,
) -> list[TrackedGuidance]:
    """Use consecutive GPS matches to initialize and camera crossings to move laterally."""
    if initial_votes_required < 2:
        raise ValueError("At least two GPS matches are required to initialize")
    previous_time = None
    previous_lane = None
    previous_vote = None
    consecutive_votes = 0
    output = []
    for snapshot in snapshots:
        now = snapshot.gps_second
        lanes = snapshot.lanes_left_to_right
        if len(lanes) != len(set(lanes)):
            raise ValueError("Each lane must appear once in a snapshot")
        if previous_time is not None:
            if now <= previous_time:
                raise ValueError("Snapshots must be strictly ordered by time")
            if now - previous_time > 2:
                previous_lane = previous_vote = None
                consecutive_votes = 0
        previous_time = now

        lane = _continue_lane(previous_lane, lanes, successors, map_lane_numbers) if previous_lane else None
        evidence = "map_link_continuity"
        if not lanes:
            previous_vote = None
            consecutive_votes = 0
        # Only initialize from GPS when no existing track can continue.
        if lane is None and snapshot.gps_observation:
            vote = snapshot.gps_lane_id if snapshot.gps_lane_id in lanes else None
            consecutive_votes = consecutive_votes + 1 if vote and vote == previous_vote else int(vote is not None)
            previous_vote = vote
            if consecutive_votes >= initial_votes_required:
                lane = vote
                evidence = "consecutive_gps_matches"

        if snapshot.crossing:
            if lane is None:
                evidence = "crossing_without_current_lane"
            else:
                step = -1 if snapshot.crossing == "LEFT" else 1
                neighbor = lanes.index(lane) + step
                lane = lanes[neighbor] if 0 <= neighbor < len(lanes) else None
                evidence = "confirmed_camera_crossing"

        previous_lane = lane
        if lane is None:
            output.append(TrackedGuidance(now, None, None, None, "UNKNOWN", None, evidence))
            continue
        previous_vote = None
        consecutive_votes = 0
        display_lane = lanes.index(lane) + 1
        if not snapshot.route_lane_ids:
            output.append(TrackedGuidance(now, lane, display_lane, None, "PREPARE", None, evidence))
            continue
        guidance = guidance_from_lane(lane, lanes, snapshot.route_lane_ids)
        target = lanes.index(guidance.target_lane_id) + 1 if guidance.target_lane_id else None
        output.append(TrackedGuidance(now, lane, display_lane, target, guidance.direction, guidance.lane_changes, evidence))
    return output
