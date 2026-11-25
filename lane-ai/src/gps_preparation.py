"""Prepare timestamped NMEA RMC observations for offline camera experiments."""

from bisect import bisect_right
from collections import Counter
import csv
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import tempfile

from .video_reader import read_frame_times


def _coordinate(value: str, hemisphere: str, latitude: bool) -> float:
    degrees_length = 2 if latitude else 3
    allowed = ("N", "S") if latitude else ("E", "W")
    if hemisphere not in allowed:
        raise ValueError("invalid coordinate hemisphere")
    degrees = int(value[:degrees_length])
    minutes = float(value[degrees_length:])
    limit = 90 if latitude else 180
    if not math.isfinite(minutes) or not 0 <= minutes < 60:
        raise ValueError("invalid coordinate minutes")
    result = degrees + minutes / 60
    if not 0 <= result <= limit:
        raise ValueError("coordinate outside allowed range")
    return -result if hemisphere in ("S", "W") else result


def parse_nmea(path: Path) -> tuple[list[dict], dict]:
    counts = Counter()
    gga_quality = Counter()
    observations = []
    with path.open(encoding="utf-8-sig", errors="strict") as stream:
        for line in stream:
            line = line.strip()
            if not line:
                continue
            counts["nonempty_lines"] += 1
            try:
                if not line.startswith("$"):
                    raise ValueError("missing NMEA prefix")
                body, supplied = line[1:].rsplit("*", 1)
                checksum = 0
                for char in body:
                    checksum ^= ord(char)
                if len(supplied) != 2 or checksum != int(supplied, 16):
                    raise ValueError("checksum mismatch")
            except ValueError:
                counts["invalid_sentences"] += 1
                continue
            fields = body.split(",")
            kind = fields[0][-3:]
            counts[kind] += 1
            if kind == "GGA" and len(fields) > 6:
                gga_quality[fields[6]] += 1
            if kind != "RMC":
                continue
            try:
                if len(fields) < 10:
                    raise ValueError("incomplete RMC")
                if fields[2] != "A":
                    counts["inactive_rmc"] += 1
                    continue
                # RMC carries UTC time and date. Preserve that clock without a guessed offset.
                utc = fields[1]
                date_format = "%d%m%y%H%M%S.%f" if "." in utc else "%d%m%y%H%M%S"
                timestamp = datetime.strptime(fields[9] + utc, date_format).replace(tzinfo=timezone.utc)
                speed = None
                if fields[7]:
                    knots = float(fields[7])
                    if math.isfinite(knots) and knots >= 0:
                        speed = knots * 1852 / 3600
                    else:
                        counts["invalid_speed_blank"] += 1
                bearing = None
                if fields[8]:
                    candidate = float(fields[8])
                    if math.isfinite(candidate) and 0 <= candidate < 360:
                        bearing = candidate
                    else:
                        counts["invalid_bearing_blank"] += 1
                row = {
                    "timestamp_ms": round(timestamp.timestamp() * 1000),
                    "latitude": _coordinate(fields[3], fields[4], True),
                    "longitude": _coordinate(fields[5], fields[6], False),
                    "speed_mps": speed,
                    "bearing_deg": bearing,
                }
            except (ValueError, OverflowError):
                counts["invalid_rmc"] += 1
                continue
            if observations and row["timestamp_ms"] <= observations[-1]["timestamp_ms"]:
                raise ValueError("RMC timestamps must increase strictly; inspect original duplicates/order")
            observations.append(row)
    if not observations:
        raise ValueError("no active checksum-valid RMC positions")
    return observations, {"counts": dict(counts), "gga_quality_values_raw": dict(gga_quality)}


def align_frames(frame_times: dict[int, int], gps: list[dict], max_age_ms: int):
    """Use only the preceding GPS observation; leave unsupported times unmatched."""
    timestamps = [row["timestamp_ms"] for row in gps]
    for frame_id, timestamp in frame_times.items():
        index = bisect_right(timestamps, timestamp) - 1
        position = None
        age = None
        if index < 0:
            status = "before_gps_start"
        elif timestamp > timestamps[-1]:
            status = "after_gps_end"
        else:
            age = timestamp - timestamps[index]
            if age > max_age_ms:
                status = "gps_too_old"
            else:
                status = "matched"
                position = gps[index]
        yield {"frame_id": frame_id, "timestamp_ms": timestamp, "status": status,
               "gps_timestamp_ms": position["timestamp_ms"] if position else None,
               "gps_age_ms": age,
               "latitude": position["latitude"] if position else None,
               "longitude": position["longitude"] if position else None,
               "speed_mps": position["speed_mps"] if position else None,
               "bearing_deg": position["bearing_deg"] if position else None}


def _write_csv(path: Path, rows: list[dict]) -> None:
    with path.open("x", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def prepare_drive(source: Path, session: Path, max_age_ms: int = 2000) -> dict:
    if max_age_ms < 0:
        raise ValueError("max_age_ms must be non-negative")
    session = session.resolve()
    source_bytes = source.read_bytes()
    gps, report = parse_nmea(source)
    capture = json.loads((session / "capture_summary.json").read_text(encoding="utf-8"))
    report.update({
        "source_sha256": hashlib.sha256(source_bytes).hexdigest(),
        "source_original_name": source.name,
        "gps_rows": len(gps), "gps_first_ms": gps[0]["timestamp_ms"], "gps_last_ms": gps[-1]["timestamp_ms"],
        "gps_gaps_over_max_age": [
            {"previous_timestamp_ms": previous["timestamp_ms"], "next_timestamp_ms": current["timestamp_ms"],
             "gap_ms": current["timestamp_ms"] - previous["timestamp_ms"]}
            for previous, current in zip(gps, gps[1:])
            if current["timestamp_ms"] - previous["timestamp_ms"] > max_age_ms
        ],
        "coordinate_reference": "WGS84 degrees per NMEA RMC convention; collection method not yet verified",
        "clock_policy": "NMEA UTC and host frame UTC, offset=0; no manual shift or extrapolation",
        "match_policy": "preceding GPS only, within recorded GPS range, age <= max_age_ms",
        "max_age_ms": max_age_ms,
        "clock_alignment_verified": False, "spatial_alignment_verified": False,
        "warnings": [
            "A timestamp match is not verified sensor synchronization or spatial accuracy",
            "Negative/nonfinite speed becomes blank; original NMEA is preserved",
            "GGA quality and DOP are retained only in diagnostics; no accuracy_m is inferred",
            "Course over ground is not a reliable vehicle heading while stationary",
            "The age threshold is an initial data-preparation setting, not a validated navigation requirement",
        ], "cameras": {},
    })
    manifest = {"schema_version": 1, "session_id": session.name, "timebase": "unix_ms",
                "gps_path": "gps.csv", "cameras": [],
                "clock_alignment": "unverified: unshifted NMEA UTC and camera host-receipt UTC",
                "gps_preparation_report": "gps_preparation.json"}
    outputs = {"gps_source.nmea", "gps.csv", "recording.json", "gps_preparation.json", "gps_track.geojson"}
    mappings = {}
    for camera_id in ("left", "right"):
        video = session / f"{camera_id}.mp4"
        if not video.is_file() or video.stat().st_size == 0:
            raise ValueError(f"missing/nonempty video required: {video}")
        times = read_frame_times(session / f"{camera_id}_frames.csv")
        rows = list(align_frames(times, gps, max_age_ms))
        status_counts = dict(Counter(row["status"] for row in rows))
        if status_counts.get("matched", 0) == 0:
            raise ValueError(f"no timestamp overlap for {camera_id}")
        mappings[camera_id] = rows
        name = f"{camera_id}_gps_frames.csv"
        outputs.add(name)
        camera_report = {
            "frames": len(rows), "status_counts": status_counts,
            "gps_start_minus_camera_start_ms": gps[0]["timestamp_ms"] - next(iter(times.values())),
            "max_matched_age_ms": max(row["gps_age_ms"] for row in rows if row["status"] == "matched"),
            "frame_receipt_gaps_over_1s": [
                {"after_frame_id": previous_id, "gap_ms": current_time - previous_time}
                for (previous_id, previous_time), (_, current_time) in zip(list(times.items()), list(times.items())[1:])
                if current_time - previous_time > 1000
            ],
        }
        report["cameras"][camera_id] = camera_report
        manifest["cameras"].append({
            "camera_id": camera_id, "video_path": video.name,
            "start_timestamp_ms": next(iter(times.values())),
            "frame_timestamps_path": f"{camera_id}_frames.csv",
            "timestamp_source": "host_receipt_utc_not_sensor_exposure",
            "saved_rotation_degrees": capture["camera_configurations"][camera_id].get("saved_rotation_degrees", 0),
        })
    for name in outputs:
        if (session / name).exists():
            raise FileExistsError(f"will not overwrite prepared file: {session / name}")
    # Stage complete generated files before publishing them beside the unchanged videos/CSVs.
    with tempfile.TemporaryDirectory(prefix="gps_prepare_", dir=session) as temporary:
        staging = Path(temporary)
        (staging / "gps_source.nmea").write_bytes(source_bytes)
        _write_csv(staging / "gps.csv", gps)
        for camera_id, rows in mappings.items():
            _write_csv(staging / f"{camera_id}_gps_frames.csv", rows)
        geojson = {"type": "FeatureCollection", "features": [
            {"type": "Feature", "geometry": {"type": "Point", "coordinates": [r["longitude"], r["latitude"]]},
             "properties": {"timestamp_ms": r["timestamp_ms"], "speed_mps": r["speed_mps"]}}
            for r in gps
        ]}
        for name, data in (("gps_preparation.json", report), ("recording.json", manifest), ("gps_track.geojson", geojson)):
            (staging / name).write_text(json.dumps(data, indent=2, ensure_ascii=False, allow_nan=False), encoding="utf-8")
        for name in sorted(outputs):
            (staging / name).rename(session / name)
    return report
