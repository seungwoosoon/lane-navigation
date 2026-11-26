"""Run offline detection and lane guidance from a recorded dual-camera approach."""

import argparse
from bisect import bisect_left, bisect_right
from collections import Counter
import csv
import json
from math import isfinite
from pathlib import Path
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from src.approach_map import ApproachMap, project_gps
from src.approach_vision import ApproachVision
from src.bev import GroundProjection
from src.detection import UfldDetector
from src.lane_guidance import guidance_from_lane
from src.lane_tracking import LaneSnapshot, replay_lane_tracking
from src.video_reader import read_frame_times


class RecordingCamera:
    def __init__(self, session, spec):
        self.times = read_frame_times(session / spec["frame_timestamps_path"])
        self.ids, self.stamps = list(self.times), list(self.times.values())
        self.capture = cv2.VideoCapture(str(session / spec["video_path"]))
        self.last_read_id = -1

    def frame(self, target_ms):
        pos = bisect_left(self.stamps, target_ms)
        options = (i for i in (pos - 1, pos) if 0 <= i < len(self.stamps))
        index = min(options, key=lambda i: abs(self.stamps[i] - target_ms))
        stamp, frame_id = self.stamps[index], self.ids[index]
        if abs(stamp - target_ms) > 100 or frame_id <= self.last_read_id:
            return None
        current = round(self.capture.get(cv2.CAP_PROP_POS_FRAMES))
        if current > frame_id or frame_id - current > 60:
            if not self.capture.set(cv2.CAP_PROP_POS_FRAMES, frame_id):
                raise ValueError(f"Cannot seek to frame {frame_id}")
            current = frame_id
        while current < frame_id:
            if not self.capture.grab():
                raise ValueError(f"Cannot advance to frame {frame_id}")
            current += 1
        read, image = self.capture.read()
        if not read or round(self.capture.get(cv2.CAP_PROP_POS_FRAMES)) != frame_id + 1:
            raise ValueError(f"Cannot decode frame {frame_id}")
        self.last_read_id = frame_id
        return frame_id, stamp, image


def gps_fixes(path, start, end, hdmap):
    fixes = {}
    with path.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            stamp = int(row["timestamp_ms"])
            if start <= stamp < end:
                lat, lon = float(row["latitude"]), float(row["longitude"])
                if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                    continue
                point = project_gps(lat, lon)
                link = hdmap.nearest(point)
                order = hdmap.lane_order(link) if link else ()
                fixes[stamp] = (point, link, order)
    return fixes


def observations(args, cameras, grids, vision):
    detector = UfldDetector(args.weights, device=args.device, threads=args.threads)
    results = []
    for index, grid in enumerate(grids, 1):
        metrics = {}
        crossing = None
        edge = False
        ready = grid
        for name, camera in cameras.items():
            packet = camera.frame(grid)
            if packet is None:
                if name == "right":
                    vision.missing_frame()
                continue
            frame_id, stamp, image = packet
            if name == "right" and image.shape[:2] != (vision.projection.height, vision.projection.width):
                raise ValueError("Right camera frame dimensions differ from the BEV configuration")
            start = time.perf_counter()
            obs = detector.detect(image, name, frame_id, stamp, None, "frame_timestamp_csv")
            metrics[name] = {
                "frame_id": frame_id, "timestamp_ms": stamp,
                "boundaries_with_output": sum(b.valid for b in obs.boundaries),
                "inference_ms": round((time.perf_counter() - start) * 1000, 2),
            }
            ready = max(ready, stamp)
            if name == "right":
                crossing = vision.crossing(grid, obs)
                edge = vision.road_edge(image, obs)
        results.append((grid, ready, metrics, crossing, edge))
        if index % 25 == 0:
            print(f"Processed {index}/{len(grids)} synchronized samples", flush=True)
    return results


def replay_gps(frames, gps, hdmap, targets):
    stamps = sorted(gps)
    events = {ready: crossing for _, ready, _, crossing, _ in frames if crossing}
    timeline = sorted(set(stamps) | {ready for _, ready, *_ in frames})
    snapshots = []
    for stamp in timeline:
        index = bisect_right(stamps, stamp) - 1
        fix = gps[stamps[index]] if index >= 0 and stamp - stamps[index] <= 2000 else None
        _, link, order = fix if fix else (None, None, ())
        snapshots.append(LaneSnapshot(
            stamp / 1000, order, link, targets.intersection(order), events.get(stamp), stamp in gps,
        ))
    tracked = replay_lane_tracking(snapshots, hdmap.successors, hdmap.numbers)
    return {stamp: (row.map_lane_id, row.evidence) for stamp, row in zip(timeline, tracked)}


def replay_edge(frames, gps, hdmap):
    stamps = sorted(gps)
    tracked, last = {}, None
    for _, stamp, _, crossing, edge in frames:
        index = bisect_right(stamps, stamp) - 1
        fix = gps[stamps[index]] if index >= 0 and stamp - stamps[index] <= 2000 else None
        _, _, order = fix if fix else (None, None, ())
        outer = order[-1] if order else None
        if crossing or (last and order != last[2]):
            last = None
        if edge and outer in hdmap.right_edges:
            last = stamp, outer, order, np.array(fix[0])
            tracked[stamp] = outer, "camera_road_edge"
            continue
        if last and stamp - last[0] <= 1000 and order == last[2]:
            points = hdmap.links[last[1]].parts[0]
            direction = points[-1] - points[0]
            normal = np.array([direction[1], -direction[0]])
            length = np.linalg.norm(normal)
            if length and abs(np.dot(np.array(fix[0]) - last[3], normal / length)) <= 1:
                tracked[stamp] = last[1], "short_continuity"
                continue
        tracked[stamp] = None, "road_edge_not_confirmed"
    return tracked


def analyze(args):
    started = time.perf_counter()
    metadata = json.loads((args.session / "recording.json").read_text(encoding="utf-8-sig"))
    if metadata["timebase"] != "unix_ms":
        raise ValueError("Recording must provide Unix millisecond timestamps")
    config = json.loads(args.calibration.read_text(encoding="utf-8-sig"))
    hdmap = ApproachMap(args.hdmap)
    target_links = hdmap.route_targets(args.route_link) | frozenset(args.target_link)
    if not target_links.issubset(hdmap.links):
        raise ValueError("A target link is absent from the supplied A2 map")
    cameras = {}
    try:
        for spec in metadata["cameras"]:
            if spec["camera_id"] in ("left", "right"):
                name = spec["camera_id"]
                if name in cameras:
                    raise ValueError(f"Duplicate camera ID {name}")
                cameras[name] = RecordingCamera(args.session, spec)
        if set(cameras) != {"left", "right"} or any(not c.capture.isOpened() for c in cameras.values()):
            raise ValueError("Both recorded cameras must be available")
        origin = min(c.stamps[0] for c in cameras.values())
        start, end = origin + round(args.start * 1000), origin + round(args.end * 1000)
        camera_start = origin + round(args.camera_from * 1000)
        warmup = origin + round(args.gps_from * 1000)
        grids = range(camera_start, end, 1000 // args.sample_fps)
        if not grids or grids[-1] > min(c.stamps[-1] for c in cameras.values()) + 100:
            raise ValueError("Requested range is outside the common recorded footage")
        gps = gps_fixes(args.session / metadata["gps_path"], warmup, end + 101, hdmap)
        vision = ApproachVision(
            GroundProjection(**config["common"], **config["cameras"]["right"]),
            config["camera_ground_offsets_m"]["right"][0],
        )
        frames = observations(args, cameras, grids, vision)
    finally:
        for camera in cameras.values():
            camera.capture.release()
    tracked = replay_edge(frames, gps, hdmap) if args.anchor == "road-edge" else replay_gps(frames, gps, hdmap, target_links)
    stamps = sorted(gps)
    samples = []
    for grid, ready, metrics, crossing, edge in frames:
        if grid < start:
            continue
        index = bisect_right(stamps, ready) - 1
        order = gps[stamps[index]][2] if index >= 0 and ready - stamps[index] <= 2000 else ()
        lane, evidence = tracked[ready]
        guidance = guidance_from_lane(lane, order, target_links)
        current = None
        if lane in order:
            current = int(hdmap.numbers[lane]) if args.numbering == "map" else order.index(lane) + 1
        target = guidance.target_lane_id
        target_number = None
        if target:
            target_number = int(hdmap.numbers[target]) if args.numbering == "map" else order.index(target) + 1
        samples.append({
            "timestamp_ms": ready, "recording_elapsed_ms": grid - origin,
            "status": "VALID" if current is not None else "UNKNOWN",
            "current_lane": current, "map_link_id": lane, "evidence": evidence,
            "target_lane": target_number,
            "movement": guidance.direction if target else None,
            "lane_changes": guidance.lane_changes, "camera_crossing": crossing,
            "right_edge_confirmed": edge, "camera": metrics,
        })
    return {
        "session_id": metadata["session_id"], "anchor": args.anchor, "lane_numbering": args.numbering,
        "route_link": args.route_link, "sample_fps": args.sample_fps,
        "explicit_target_links": args.target_link,
        "processed_frames": sum(len(metrics) for _, _, metrics, _, _ in frames),
        "status_counts": dict(Counter(r["status"] for r in samples)),
        "elapsed_seconds": round(time.perf_counter() - started, 3),
        "samples": samples,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session", type=Path)
    parser.add_argument("--hdmap", type=Path, required=True)
    parser.add_argument("--calibration", type=Path, required=True)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--start", type=float, required=True, help="Seconds from recording start")
    parser.add_argument("--end", type=float, required=True)
    parser.add_argument("--gps-from", type=float, help="Optional earlier start for GPS initialization")
    parser.add_argument("--camera-from", type=float, help="Optional camera warmup before the output range")
    parser.add_argument("--anchor", choices=("gps", "road-edge"), default="gps")
    parser.add_argument("--numbering", choices=("physical", "map"), default="physical")
    route = parser.add_mutually_exclusive_group()
    route.add_argument("--route-link", help="Optional A2 maneuver link supplied by the planned route")
    route.add_argument("--target-link", action="append", default=[], help="Explicit A2 approach target; repeat as needed")
    parser.add_argument("--sample-fps", type=int, choices=(1, 2, 4, 5, 8, 10), default=5)
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.camera_from is None:
        args.camera_from = args.start
    if args.gps_from is None:
        args.gps_from = args.camera_from
    timing = (args.gps_from, args.camera_from, args.start, args.end)
    if not all(map(isfinite, timing)) or not 0 <= min(timing[:2]) <= max(timing[:2]) <= args.start < args.end:
        parser.error("Expected non-negative warmup starts at or before the output start, and start < end")
    if args.output.exists():
        parser.error("Choose a new output file")
    result = analyze(args)
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(f"{result['processed_frames']} frames; {result['status_counts']}; {result['elapsed_seconds']} seconds")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
