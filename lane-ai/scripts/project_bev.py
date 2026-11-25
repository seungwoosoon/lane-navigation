"""Convert saved UFLD boundary points to provisional ground coordinates."""

import argparse
from collections import Counter
import json
from math import isfinite
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from src.bev import GroundProjection


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--boundaries", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    projections = {camera_id: GroundProjection(**config["common"], **pose)
                   for camera_id, pose in config["cameras"].items()}
    offsets = config.get("camera_ground_offsets_m")
    if offsets is not None:
        if not isinstance(offsets, dict) or offsets.keys() != projections.keys():
            parser.error("provide one ground offset for every configured camera")
        for offset in offsets.values():
            if (not isinstance(offset, list) or len(offset) != 2 or any(
                    type(value) not in (int, float) or not isfinite(value) for value in offset)):
                parser.error("each ground offset must contain finite right and forward distances in metres")
    args.output.mkdir(parents=True, exist_ok=False)
    totals = {camera_id: Counter() for camera_id in projections}
    with args.boundaries.open(encoding="utf-8") as source, (args.output / "ground_boundaries.jsonl").open("x", encoding="utf-8") as output:
        for line in source:
            observation = json.loads(line)
            camera_id = observation["camera_id"]
            projection = projections[camera_id]
            origin = f"ground_below_{camera_id}_lens"
            offset_x, offset_y = 0.0, 0.0
            if offsets is not None:
                origin = "vehicle_centerline_at_camera_row"
                offset_x, offset_y = offsets[camera_id]
            if (observation["image_width"], observation["image_height"]) != (projection.width, projection.height):
                raise ValueError(f"unexpected image size for {camera_id}")
            counts = totals[camera_id]
            counts["frames"] += 1
            boundaries = []
            for boundary in observation["boundaries"]:
                if not boundary["valid"]:
                    continue
                pixels = boundary["points_xy"]
                points = []
                for u, v in pixels:
                    point = projection.project(u, v)
                    if point is not None:
                        points.append((point[0] + offset_x, point[1] + offset_y))
                counts["input_points"] += len(pixels)
                counts["projected_points"] += len(points)
                if points:
                    boundaries.append({"boundary_id": boundary["boundary_id"], "ground_xy_m": points})
            counts["frames_with_ground_points"] += bool(boundaries)
            result = {key: observation[key] for key in ("camera_id", "frame_id", "timestamp_ms")}
            result.update(origin=origin, boundaries=boundaries)
            output.write(json.dumps(result, allow_nan=False) + "\n")
    reference = "vehicle centerline at camera row" if offsets is not None else "ground below each lens"
    summary = {"config": config, "coordinates": f"x right, y forward, metres from {reference}",
               "source": str(args.boundaries), "cameras": totals}
    formatted = json.dumps(summary, indent=2, allow_nan=False)
    (args.output / "summary.json").write_text(formatted + "\n", encoding="utf-8")
    print(formatted)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
