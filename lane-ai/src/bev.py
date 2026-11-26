"""Project image points to a flat ground plane using an undistorted pinhole model."""

from dataclasses import dataclass
from math import cos, hypot, isfinite, radians, sin, tan


@dataclass(frozen=True)
class GroundProjection:
    width: int
    height: int
    height_m: float
    horizontal_fov_deg: float
    yaw_deg: float
    pitch_down_deg: float
    max_range_m: float

    def __post_init__(self):
        values = (self.height_m, self.horizontal_fov_deg, self.yaw_deg,
                  self.pitch_down_deg, self.max_range_m)
        if (self.width <= 0 or self.height <= 0 or not all(map(isfinite, values))
                or self.height_m <= 0 or self.max_range_m <= 0
                or not 0 < self.horizontal_fov_deg < 180 or not -90 < self.pitch_down_deg < 90):
            raise ValueError("invalid ground projection parameters")

    def project(self, u: float, v: float) -> tuple[float, float] | None:
        """Return rightward and forward metres from this lens's point on the ground."""
        if not (0 <= u < self.width and 0 <= v < self.height):
            return None
        focal_px = self.width / (2 * tan(radians(self.horizontal_fov_deg) / 2))
        right = (u - (self.width - 1) / 2) / focal_px
        down = (v - (self.height - 1) / 2) / focal_px
        pitch = radians(self.pitch_down_deg)
        forward = cos(pitch) - down * sin(pitch)
        ground_down = sin(pitch) + down * cos(pitch)
        if ground_down <= 1e-9:
            return None
        scale = self.height_m / ground_down
        yaw = radians(self.yaw_deg)
        x = scale * (cos(yaw) * right + sin(yaw) * forward)
        y = scale * (cos(yaw) * forward - sin(yaw) * right)
        return (x, y) if y > 0 and hypot(x, y) <= self.max_range_m else None
