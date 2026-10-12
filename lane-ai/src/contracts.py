"""Internal draft contracts, independent of Android transport and UFLD internals."""

from dataclasses import dataclass
from math import isfinite
from typing import Literal


@dataclass(frozen=True)
class PixelBoundary:
    """Points are (x, y) pixels in the ORIGINAL image, not model input pixels.

    quality is optional and detector-specific; it is not lane confidence.
    """

    boundary_id: str
    points_xy: tuple[tuple[float, float], ...]
    valid: bool
    quality: float | None = None


@dataclass(frozen=True)
class CameraObservation:
    camera_id: str
    frame_id: int
    timestamp_ms: int | None
    image_width: int
    image_height: int
    boundaries: tuple[PixelBoundary, ...]
    video_time_ms: float | None = None
    timestamp_source: str = "unavailable"


@dataclass(frozen=True)
class CandidateScore:
    """Comparable score within one candidate set; higher is better.

    This is NOT a probability. Real scoring terms and their scale remain TBD.
    """

    lane_id: str
    score: float
    evidence: tuple[str, ...]


@dataclass(frozen=True)
class LaneEstimate:
    """Output contract only. No inference or confidence calibration is provided.

    lane_number must use the team's agreed numbering convention. Labels and
    ground-truth lane numbers must never be passed into an inference module.
    """

    timestamp_ms: int
    road_segment_id: str | None
    lane_id: str | None
    lane_number: int | None
    confidence: float | None
    status: Literal["VALID", "UNKNOWN"]
    confidence_method: str | None = None
    reason: str | None = None

    def __post_init__(self) -> None:
        if self.status not in ("VALID", "UNKNOWN"):
            raise ValueError("status must be VALID or UNKNOWN")
        if self.status == "UNKNOWN" and (self.lane_id is not None or self.lane_number is not None):
            raise ValueError("UNKNOWN must not assign a lane")
        if self.status == "VALID" and (
            not self.road_segment_id or not self.lane_id or self.lane_number is None
        ):
            raise ValueError("VALID requires a road, lane ID and agreed lane number")
        if self.confidence is not None:
            if isinstance(self.confidence, bool) or not isfinite(self.confidence) or not 0 <= self.confidence <= 1:
                raise ValueError("confidence must be finite and within [0, 1]")
            if not self.confidence_method:
                raise ValueError("confidence requires a documented method")
        if self.status == "VALID" and self.confidence is None:
            raise ValueError("VALID requires confidence")
