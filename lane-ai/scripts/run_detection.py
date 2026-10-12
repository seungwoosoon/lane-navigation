"""Offline UFLD v2 CLI. No APP/server connection or camera calibration required."""

import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def main() -> int:
    parser = argparse.ArgumentParser(description="UFLD v2 CULane ResNet18: video -> pixel coordinates + overlay")
    parser.add_argument("--video", required=True, type=Path)
    parser.add_argument("--weights", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="new directory (never overwritten)")
    parser.add_argument("--camera-id", default="sample")
    parser.add_argument("--max-frames", type=int, help="omit to process all frames")
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    parser.add_argument("--threads", type=int, default=4)
    time_group = parser.add_mutually_exclusive_group()
    time_group.add_argument("--frame-timestamps", type=Path)
    time_group.add_argument("--start-timestamp-ms", type=int)
    args = parser.parse_args()
    try:
        from src.detection_runner import run_detection
        summary = run_detection(args.video, args.weights, args.output, args.camera_id,
            args.max_frames, args.frame_timestamps, args.start_timestamp_ms, args.device, args.threads)
    except (ImportError, OSError, ValueError, RuntimeError) as exc:
        parser.exit(1, f"Detection failed: {exc}\nInstall requirements-inference.txt and check the documented inputs.\n")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
