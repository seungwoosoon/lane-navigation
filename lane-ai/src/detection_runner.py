"""Run real detector on one video; emit JSONL, overlay, preview and provenance."""

from dataclasses import asdict
import hashlib
import json
from pathlib import Path
import platform
import sys
import time

import cv2
import numpy as np
import torch
import torchvision

from .detection import PROFILE, QUALITY_METHOD, UPSTREAM_COMMIT, UfldDetector
from .video_reader import VideoReader


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def draw_observation(frame, observation):
    image = frame.copy()
    colors = ((255, 180, 0), (0, 220, 0), (0, 180, 255), (220, 0, 220))
    for boundary, color in zip(observation.boundaries, colors):
        if not boundary.valid:
            continue
        for x, y in boundary.points_xy:
            cv2.circle(image, (round(x), round(y)), 3, color, -1)
        x, y = boundary.points_xy[len(boundary.points_xy) // 2]
        cv2.putText(image, boundary.boundary_id, (round(x), round(y)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.55, color, 2)
    cv2.putText(image, f"UFLDv2 | frame {observation.frame_id} | boundary slots, NOT lane number",
                (12, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 255, 255), 2)
    return image


def run_detection(video: Path, weights: Path, output: Path, camera_id: str = "sample",
                  max_frames: int | None = None, timestamps: Path | None = None,
                  start_timestamp_ms: int | None = None, device: str = "cpu", threads: int = 4) -> dict:
    if not camera_id.strip():
        raise ValueError("camera_id must not be empty")
    if max_frames is not None and max_frames < 1:
        raise ValueError("max_frames must be positive")
    if output.exists():
        raise FileExistsError(f"output already exists; choose a new directory: {output}")
    detector = UfldDetector(weights, device=device, threads=threads)
    writer = None
    count = valid_frames = valid_boundaries = 0
    inference_seconds = 0.0
    dimensions = None
    sources = set()
    # Create outputs only after validating model and video inputs.
    with VideoReader(video, timestamps, start_timestamp_ms) as reader:
        output.mkdir(parents=True, exist_ok=False)
        wall_start = time.perf_counter()
        try:
            with (output / "boundaries.jsonl").open("x", encoding="utf-8") as records:
                for packet in reader.frames(max_frames):
                    height, width = packet.image_bgr.shape[:2]
                    if dimensions is None:
                        dimensions = (width, height)
                        writer = cv2.VideoWriter(str(output / "overlay.mp4"), cv2.VideoWriter_fourcc(*"mp4v"),
                                                 reader.fps, dimensions)
                        if not writer.isOpened():
                            raise RuntimeError("OpenCV could not open mp4v output writer")
                    elif dimensions != (width, height):
                        raise ValueError("input frame dimensions changed during the video")
                    infer_start = time.perf_counter()
                    observation = detector.detect(packet.image_bgr, camera_id, packet.frame_id,
                        packet.timestamp_ms, packet.video_time_ms, packet.timestamp_source)
                    inference_seconds += time.perf_counter() - infer_start
                    sources.add(packet.timestamp_source)
                    valid = sum(boundary.valid for boundary in observation.boundaries)
                    valid_frames += int(valid > 0)
                    valid_boundaries += valid
                    records.write(json.dumps(asdict(observation), allow_nan=False) + "\n")
                    overlay = draw_observation(packet.image_bgr, observation)
                    writer.write(overlay)
                    if count == 0 and not cv2.imwrite(str(output / "preview.jpg"), overlay):
                        raise RuntimeError("preview image could not be saved")
                    count += 1
                    if count % 30 == 0:
                        print(f"processed {count} frames", file=sys.stderr, flush=True)
        finally:
            if writer is not None:
                writer.release()
        elapsed = time.perf_counter() - wall_start
        if not count:
            raise ValueError("no frames processed")
        summary = {
            "status": "complete", "profile": PROFILE, "upstream_commit": UPSTREAM_COMMIT,
            "input": str(video.resolve()), "input_sha256": file_sha256(video),
            "weights": str(weights.resolve()), "weights_sha256": file_sha256(weights),
            "camera_id": camera_id, "device": device, "threads": threads,
            "processed_frames": count, "reported_input_frames": reader.reported_frame_count,
            "max_frames": max_frames, "source_fps": reader.fps, "image_size": dimensions,
            "frames_with_boundaries": valid_frames, "valid_boundary_count": valid_boundaries,
            "inference_seconds": inference_seconds, "inference_fps": count / inference_seconds,
            "processing_seconds": elapsed, "timestamp_sources": sorted(sources),
            "quality_method": QUALITY_METHOD,
            "versions": {"python": platform.python_version(), "torch": torch.__version__,
                         "torchvision": torchvision.__version__, "opencv": cv2.__version__, "numpy": np.__version__},
            "limitations": ["Detection smoke test, not lane accuracy evaluation",
                "No calibration, HD Map matching, absolute lane number, or left/right view validation",
                "Overlay is constant nominal FPS and contains no audio; use JSONL timing for analysis"],
        }
        (output / "summary.json").write_text(json.dumps(summary, indent=2, allow_nan=False), encoding="utf-8")
        return summary
