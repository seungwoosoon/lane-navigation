"""Sequential OpenCV input with explicit distinction between UTC and video time."""

import csv
from dataclasses import dataclass
import math
from pathlib import Path

import cv2
import numpy as np


@dataclass(frozen=True)
class VideoFrame:
    frame_id: int
    image_bgr: np.ndarray
    video_time_ms: float
    timestamp_ms: int | None
    timestamp_source: str


def read_frame_times(path: Path) -> dict[int, int]:
    result = {}
    previous_time = -1
    previous_frame = -1
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        fields = reader.fieldnames or []
        if not {"frame_id", "timestamp_ms"}.issubset(fields) or len(fields) != len(set(fields)):
            raise ValueError("frame timestamp CSV needs unique frame_id,timestamp_ms headers")
        for row in reader:
            if None in row or None in row.values():
                raise ValueError("frame timestamp CSV has malformed columns")
            frame, timestamp = int(row["frame_id"]), int(row["timestamp_ms"])
            if frame <= previous_frame or timestamp <= previous_time:
                raise ValueError("frame IDs and timestamps must be non-negative and strictly increasing")
            result[frame] = timestamp
            previous_frame, previous_time = frame, timestamp
    if not result:
        raise ValueError("frame timestamp CSV is empty")
    return result


class VideoReader:
    def __init__(self, path: Path, timestamps: Path | None = None, start_timestamp_ms: int | None = None):
        if timestamps is not None and start_timestamp_ms is not None:
            raise ValueError("choose frame timestamps OR start time, not both")
        if start_timestamp_ms is not None and start_timestamp_ms < 0:
            raise ValueError("start_timestamp_ms must be non-negative")
        if not path.is_file():
            raise FileNotFoundError(f"video not found: {path}")
        self.times = read_frame_times(timestamps) if timestamps else None
        self.start = start_timestamp_ms
        self.capture = cv2.VideoCapture(str(path))
        if not self.capture.isOpened():
            self.capture.release()
            raise ValueError(f"cannot decode video: {path}")
        self.fps = float(self.capture.get(cv2.CAP_PROP_FPS))
        self.reported_frame_count = int(self.capture.get(cv2.CAP_PROP_FRAME_COUNT))
        if not math.isfinite(self.fps) or self.fps <= 0:
            self.capture.release()
            raise ValueError("video FPS is unavailable; inspect/convert the source with explicit timing first")

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.capture.release()

    def frames(self, max_frames: int | None = None):
        if max_frames is not None and max_frames < 1:
            raise ValueError("max_frames must be positive")
        frame_id = 0
        previous_time = -1.0
        origin = None
        use_estimate = False
        while max_frames is None or frame_id < max_frames:
            success, image = self.capture.read()
            if not success:
                if frame_id == 0:
                    raise ValueError("video contains no decodable frames")
                break
            raw_time = float(self.capture.get(cv2.CAP_PROP_POS_MSEC))
            relative_source = "container_time"
            if origin is None and math.isfinite(raw_time) and raw_time >= 0:
                origin = raw_time
            video_time = raw_time - origin if origin is not None else float("nan")
            if use_estimate or not math.isfinite(video_time) or video_time < 0 or video_time <= previous_time:
                use_estimate = True
                video_time = 0.0 if frame_id == 0 else previous_time + 1000.0 / self.fps
                relative_source = "nominal_fps_estimate"
            previous_time = video_time
            if self.times is not None:
                if frame_id not in self.times:
                    raise ValueError(f"no measured timestamp for decoded frame {frame_id}")
                timestamp = self.times[frame_id]
                source = "frame_timestamp_csv"
            elif self.start is not None:
                timestamp = self.start + round(video_time)
                source = f"start_plus_{relative_source}; clock_alignment_unverified"
            else:
                timestamp = None
                source = f"relative_{relative_source}; UTC_unavailable"
            yield VideoFrame(frame_id, image, video_time, timestamp, source)
            frame_id += 1
