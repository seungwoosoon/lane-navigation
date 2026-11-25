"""Record two DirectShow cameras with host-receipt timestamps for offline tests."""

import argparse
import csv
from datetime import datetime, timezone
import json
from pathlib import Path
import time

import cv2


def fourcc_name(value: float) -> str:
    code = int(value)
    if code == 0:
        return "unknown"
    return "".join(chr((code >> (8 * offset)) & 0xFF) for offset in range(4))


def main() -> int:
    parser = argparse.ArgumentParser(description="Record left/right MP4 plus host-receipt frame times")
    parser.add_argument("--camera-ids", nargs=2, type=int, default=(1, 2), metavar=("LEFT", "RIGHT"))
    parser.add_argument("--width", type=int, default=1280)
    parser.add_argument("--height", type=int, default=720)
    parser.add_argument("--fps", type=int, default=30, help="requested capture and nominal MP4 playback FPS")
    parser.add_argument("--duration", type=float, help="optional recording duration in wall-clock seconds; omit to stop with Ctrl+C")
    parser.add_argument("--preview", action="store_true", help="show both cameras during recording; press q in a preview window to stop")
    parser.add_argument("--rotate-180", nargs="+", type=int, default=(), metavar="ID",
                        help="rotate these camera indexes by 180 degrees in both saved video and preview")
    parser.add_argument("--output", required=True, type=Path, help="new directory; existing data is never overwritten")
    args = parser.parse_args()
    if len(set(args.camera_ids)) != 2 or any(index < 0 for index in args.camera_ids):
        parser.error("choose two different non-negative camera indexes")
    if args.width < 1 or args.height < 1 or args.fps < 1 or (args.duration is not None and args.duration <= 0):
        parser.error("width, height, fps and duration (when supplied) must be positive")
    if not set(args.rotate_180).issubset(args.camera_ids):
        parser.error("--rotate-180 indexes must be included in --camera-ids")
    if args.output.exists():
        parser.error(f"output already exists: {args.output}")

    cameras = []
    video_writers = []
    csv_files = []
    records = {"left": 0, "right": 0}
    first_ms = {"left": None, "right": None}
    last_ms = {"left": None, "right": None}
    configurations = {}
    status = "complete"
    error = None
    started = None
    preview_windows = {}
    try:
        for label, index in zip(("left", "right"), args.camera_ids):
            camera = cv2.VideoCapture(index, cv2.CAP_DSHOW)
            if not camera.isOpened():
                camera.release()
                raise RuntimeError(f"cannot open {label} camera {index}")
            cameras.append(camera)
            camera.set(cv2.CAP_PROP_FRAME_WIDTH, args.width)
            camera.set(cv2.CAP_PROP_FRAME_HEIGHT, args.height)
            camera.set(cv2.CAP_PROP_FPS, args.fps)
            camera.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))
            configuration = {
                "index": index,
                "saved_rotation_degrees": 180 if index in args.rotate_180 else 0,
                "reported_width": camera.get(cv2.CAP_PROP_FRAME_WIDTH),
                "reported_height": camera.get(cv2.CAP_PROP_FRAME_HEIGHT),
                "reported_fps": camera.get(cv2.CAP_PROP_FPS),
                "reported_fourcc": fourcc_name(camera.get(cv2.CAP_PROP_FOURCC)),
            }
            configurations[label] = configuration
            print(f"{label}: {configuration}", flush=True)
            if (round(configuration["reported_width"]) != args.width
                    or round(configuration["reported_height"]) != args.height
                    or configuration["reported_fourcc"] != "MJPG"):
                raise RuntimeError(f"{label} camera did not report requested resolution/MJPG")

        args.output.mkdir(parents=True, exist_ok=False)
        for label in ("left", "right"):
            writer = cv2.VideoWriter(str(args.output / f"{label}.mp4"),
                                     cv2.VideoWriter_fourcc(*"mp4v"), args.fps,
                                     (args.width, args.height))
            if not writer.isOpened():
                writer.release()
                raise RuntimeError(f"cannot create {label}.mp4")
            video_writers.append(writer)
            stream = (args.output / f"{label}_frames.csv").open("x", encoding="utf-8", newline="")
            csv_files.append(stream)
        csv_writers = [csv.writer(stream) for stream in csv_files]
        for writer in csv_writers:
            writer.writerow(("frame_id", "timestamp_ms"))

        if args.preview:
            for label, index in zip(("left", "right"), args.camera_ids):
                window = f"{label.upper()} - camera {index} - REC"
                cv2.namedWindow(window, cv2.WINDOW_AUTOSIZE)
                preview_windows[label] = window
            print("Press q in either preview window to stop and save both videos.", flush=True)
        utc_anchor_ns = time.time_ns()
        monotonic_anchor_ns = time.perf_counter_ns()
        started = time.perf_counter()
        next_preview = started
        print("Recording... Press Ctrl+C to stop and finalize both MP4 files. CSV times are host receipt times.", flush=True)
        while args.duration is None or time.perf_counter() - started < args.duration:
            refresh_preview = args.preview and time.perf_counter() >= next_preview
            for label, index, camera, writer, csv_writer in zip(
                    ("left", "right"), args.camera_ids, cameras, video_writers, csv_writers):
                ok, frame = camera.read()
                received_ns = time.perf_counter_ns()
                if not ok or frame is None:
                    raise RuntimeError(f"{label} camera stopped after {records[label]} frames")
                if frame.shape[:2] != (args.height, args.width):
                    raise RuntimeError(f"{label} frame size changed to {frame.shape[1]}x{frame.shape[0]}")
                if index in args.rotate_180:
                    frame = cv2.rotate(frame, cv2.ROTATE_180)
                timestamp_ms = (utc_anchor_ns + received_ns - monotonic_anchor_ns) // 1_000_000
                writer.write(frame)
                csv_writer.writerow((records[label], timestamp_ms))
                first_ms[label] = first_ms[label] if first_ms[label] is not None else timestamp_ms
                last_ms[label] = timestamp_ms
                records[label] += 1
                if refresh_preview:
                    preview = cv2.resize(frame, (640, max(1, round(640 * args.height / args.width))),
                                         interpolation=cv2.INTER_AREA)
                    cv2.imshow(preview_windows[label], preview)
            if refresh_preview:
                next_preview = time.perf_counter() + 0.1
            if args.preview and cv2.waitKey(1) & 0xFF == ord("q"):
                status = "stopped_by_user"
                break
    except KeyboardInterrupt:
        status = "stopped_by_user"
    except (OSError, RuntimeError, cv2.error) as exc:
        status = "failed"
        error = str(exc)
    finally:
        elapsed = time.perf_counter() - started if started is not None else 0.0
        for stream in csv_files:
            stream.close()
        for writer in video_writers:
            writer.release()
        for camera in cameras:
            camera.release()
        if preview_windows:
            cv2.destroyAllWindows()
        if args.output.exists():
            summary = {
                "status": status,
                "error": error,
                "created_utc": datetime.now(timezone.utc).isoformat(),
                "camera_configurations": configurations,
                "requested_width": args.width,
                "requested_height": args.height,
                "requested_fps": args.fps,
                "video_codec": "mp4v",
                "video_timing": "constant nominal FPS; use frame CSV for host-receipt time",
                "timestamp_source": "host_receive_time_utc_estimated_from_monotonic_clock; not_exposure_time",
                "synchronization": "unverified; cameras read sequentially",
                "elapsed_seconds": elapsed,
                "cameras": {
                    label: {"device_index": index, "frames_written": records[label],
                            "application_record_fps": records[label] / elapsed if elapsed > 0 else 0,
                            "first_timestamp_ms": first_ms[label], "last_timestamp_ms": last_ms[label]}
                    for label, index in zip(("left", "right"), args.camera_ids)
                },
            }
            (args.output / "capture_summary.json").write_text(
                json.dumps(summary, indent=2, ensure_ascii=False, allow_nan=False), encoding="utf-8")
            print(json.dumps(summary, indent=2, ensure_ascii=False), flush=True)
    return 0 if status in ("complete", "stopped_by_user") else 1


if __name__ == "__main__":
    raise SystemExit(main())
