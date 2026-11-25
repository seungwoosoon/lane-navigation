"""Experimental right-camera evidence for offline approach tracking."""

from collections import deque
from itertools import combinations
from math import cos, radians, sin, tan
from statistics import median

import cv2
import numpy as np

from .bev import GroundProjection
from .contracts import CameraObservation, PixelBoundary
from .lane_tracking import Crossing


def _translation(before: list[float], after: list[float]):
    matches = []
    for count in range(2, min(len(before), len(after)) + 1):
        for left in combinations(before, count):
            for right in combinations(after, count):
                shifts = [b - a for a, b in zip(left, right)]
                shift = median(shifts)
                spread = max(shifts) - min(shifts)
                if .55 <= abs(shift) <= 2.5 and spread <= .45:
                    matches.append((count, spread, shift, tuple(zip(left, right))))
    if not matches:
        return None
    best = min(matches, key=lambda match: (-match[0], match[1]))
    if any(match[0] == best[0] and match[2] * best[2] < 0 for match in matches):
        return None
    return best


class ApproachVision:
    def __init__(self, projection: GroundProjection, lateral_offset_m: float):
        self.projection = projection
        self.lateral_offset_m = lateral_offset_m
        self._history: dict[int, list[float]] = {}
        self._last_crossing_ms = float("-inf")
        self._road_edge_votes = deque(maxlen=5)

    def _ground(self, boundary: PixelBoundary) -> list[tuple[float, float]]:
        if not boundary.valid:
            return []
        points = (self.projection.project(u, v) for u, v in boundary.points_xy)
        return [(p[0] + self.lateral_offset_m, p[1]) for p in points if p is not None]

    def _offsets(self, observation: CameraObservation) -> list[float]:
        offsets = []
        for boundary in observation.boundaries:
            xy = [(x, y) for x, y in self._ground(boundary) if 3 <= y <= 14 and abs(x) < 11]
            if len(xy) < 4:
                continue
            x, y = np.array(xy).T
            if np.ptp(y) >= 4 and min(y) <= 6 <= max(y):
                slope, intercept = np.polyfit(y, x, 1)
                offsets.append(float(slope * 6 + intercept))
        return sorted(offsets)

    def crossing(self, grid_ms: int, observation: CameraObservation) -> Crossing | None:
        """Confirm at the third observation, after two matching one-second shifts."""
        self._history[grid_ms] = self._offsets(observation)
        expired = [t for t in self._history if t < grid_ms - 2000]
        for t in expired:
            del self._history[t]
        before = self._history.get(grid_ms - 2000)
        middle = self._history.get(grid_ms - 1000)
        if before is None or middle is None or grid_ms - self._last_crossing_ms < 2000:
            return None
        earlier = _translation(before, middle)
        later = _translation(middle, self._history[grid_ms])
        if not earlier or not later or earlier[2] * later[2] <= 0:
            return None
        if not any(a*b < 0 and min(abs(a), abs(b)) >= .25 for a, b in earlier[3]):
            return None
        self._last_crossing_ms = grid_ms
        return "LEFT" if earlier[2] > 0 else "RIGHT"

    def road_edge(self, image: np.ndarray, observation: CameraObservation) -> bool:
        self._road_edge_votes.append(any(self._curb_beside(image, b) for b in observation.boundaries))
        return sum(self._road_edge_votes) >= 3

    def missing_frame(self) -> None:
        self._road_edge_votes.clear()

    def _curb_beside(self, image: np.ndarray, boundary: PixelBoundary) -> bool:
        xy = [(x, y) for x, y in self._ground(boundary) if 3.5 <= y <= 13 and .7 <= x <= 5]
        if len(xy) < 7:
            return False
        x, y = np.array(xy).T
        if np.ptp(y) < 1.5 or not .9 <= np.median(x) <= 3.25:
            return False
        slope, intercept = np.polyfit(y, x, 1)
        if abs(slope) > .22 or np.median(abs(x - slope*y - intercept)) > .12:
            return False
        inside = self._luma(image, x-.30, y)
        inside_strip = self._luma(image, x+.52, y)
        outside_strip = self._luma(image, x+1.48, y)
        candidates = []
        for distance in (.82, .92, 1.02, 1.12, 1.22):
            curb = self._luma(image, x+distance, y)
            score = min(np.median(curb-inside), np.median(curb-inside_strip), np.median(curb-outside_strip))
            support = np.mean((curb > inside+4) & (curb > outside_strip+6))
            candidates.append((score, support))
        score, support = max(candidates)
        return score >= 7 and support >= .55

    def _luma(self, image: np.ndarray, x: np.ndarray, y: np.ndarray) -> np.ndarray:
        p = self.projection
        x = x - self.lateral_offset_m
        yaw, pitch = radians(p.yaw_deg), radians(p.pitch_down_deg)
        local_x, local_y = cos(yaw)*x - sin(yaw)*y, sin(yaw)*x + cos(yaw)*y
        depth = cos(pitch)*local_y + sin(pitch)*p.height_m
        down = -sin(pitch)*local_y + cos(pitch)*p.height_m
        focal = p.width / (2 * tan(radians(p.horizontal_fov_deg)/2))
        u = ((p.width-1)/2 + focal*local_x/depth).astype("float32").reshape(-1, 1)
        v = ((p.height-1)/2 + focal*down/depth).astype("float32").reshape(-1, 1)
        pixels = cv2.remap(image, u, v, cv2.INTER_LINEAR).reshape(-1, 3).astype(float)
        return pixels @ np.array([.114, .587, .299])
