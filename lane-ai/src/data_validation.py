"""Validate offline handoff files without decoding video or inventing map data."""

import csv
import json
import math
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class ValidationReport:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    details: dict = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return not self.errors

    def as_dict(self) -> dict:
        return {"ok": self.ok, "errors": self.errors, "warnings": self.warnings, "details": self.details}


def _local_file(root: Path, value: object, label: str, report: ValidationReport) -> Path | None:
    if not isinstance(value, str) or not value.strip():
        report.errors.append(f"{label}: provide a relative file path")
        return None
    try:
        relative = Path(value)
        target = (root / relative).resolve()
        if relative.is_absolute() or not target.is_relative_to(root.resolve()):
            report.errors.append(f"{label}: path must stay inside the session directory")
            return None
        if not target.is_file():
            report.errors.append(f"{label}: file does not exist: {value}")
            return None
        if target.stat().st_size == 0:
            report.errors.append(f"{label}: file is empty: {value}")
            return None
    except (OSError, ValueError) as exc:
        report.errors.append(f"{label}: cannot access path: {exc}")
        return None
    return target


def _integer(value: object) -> int:
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        raise ValueError("expected an integer")
    result = int(value)
    if result < 0:
        raise ValueError("expected a non-negative integer")
    return result


def _number(value: str, low: float, high: float = math.inf, *, exclusive_high: bool = False) -> float:
    result = float(value)
    if not math.isfinite(result) or result < low or result > high or (exclusive_high and result == high):
        raise ValueError("value outside allowed range")
    return result


def _read_times(path: Path, kind: str, report: ValidationReport) -> tuple[int, int] | None:
    required = {"timestamp_ms", "latitude", "longitude"} if kind == "GPS" else {"timestamp_ms", "frame_id"}
    times = []
    previous_frame = -1
    try:
        with path.open(encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream)
            fields = reader.fieldnames or []
            if not required.issubset(fields) or len(fields) != len(set(fields)):
                report.errors.append(f"{path.name}: unique headers required, including {sorted(required)}")
                return None
            for line, row in enumerate(reader, 2):
                try:
                    if None in row or any(value is None for value in row.values()):
                        raise ValueError("column count does not match header")
                    timestamp = _integer(row["timestamp_ms"])
                    if times and timestamp <= times[-1]:
                        raise ValueError("timestamps must increase strictly")
                    if kind == "GPS":
                        _number(row["latitude"], -90, 90)
                        _number(row["longitude"], -180, 180)
                        for name in ("accuracy_m", "speed_mps"):
                            if row.get(name, ""):
                                _number(row[name], 0)
                        if row.get("bearing_deg", ""):
                            _number(row["bearing_deg"], 0, 360, exclusive_high=True)
                    else:
                        frame = _integer(row["frame_id"])
                        if frame <= previous_frame:
                            raise ValueError("frame_id must increase strictly")
                        previous_frame = frame
                    times.append(timestamp)
                except (ValueError, TypeError) as exc:
                    report.errors.append(f"{path.name}:{line}: {exc}")
                    return None
    except (OSError, UnicodeError, csv.Error) as exc:
        report.errors.append(f"{path.name}: cannot read CSV: {exc}")
        return None
    if not times:
        report.errors.append(f"{path.name}: no data rows")
        return None
    report.details[path.name] = {"rows": len(times), "first_ms": times[0], "last_ms": times[-1]}
    if kind == "GPS" and len(times) < 2:
        report.warnings.append("GPS has only one measurement; a driving interval cannot be verified")
    return times[0], times[-1]


def validate_drive(session: Path) -> ValidationReport:
    report = ValidationReport()
    session = session.resolve()
    try:
        manifest = json.loads((session / "recording.json").read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, ValueError) as exc:
        report.errors.append(f"recording.json: cannot read manifest: {exc}")
        return report
    if not isinstance(manifest, dict):
        report.errors.append("recording.json must contain an object")
        return report
    if type(manifest.get("schema_version")) is not int or manifest["schema_version"] != 1:
        report.errors.append("schema_version must be 1")
    if manifest.get("timebase") != "unix_ms":
        report.errors.append("timebase must be unix_ms; document conversions from device clocks")
    if not isinstance(manifest.get("session_id"), str) or not manifest["session_id"].strip():
        report.errors.append("session_id is required")
    gps = _local_file(session, manifest.get("gps_path"), "gps_path", report)
    gps_range = _read_times(gps, "GPS", report) if gps else None
    cameras = manifest.get("cameras")
    if not isinstance(cameras, list) or not cameras:
        report.errors.append("cameras must be a non-empty array")
        return report
    camera_ids = set()
    intervals = []
    for index, camera in enumerate(cameras):
        label = f"cameras[{index}]"
        if not isinstance(camera, dict):
            report.errors.append(f"{label}: expected an object")
            continue
        camera_id = camera.get("camera_id")
        if not isinstance(camera_id, str) or not camera_id.strip() or camera_id in camera_ids:
            report.errors.append(f"{label}: camera_id must be non-empty and unique")
        else:
            camera_ids.add(camera_id)
        _local_file(session, camera.get("video_path"), f"{label}.video_path", report)
        try:
            start_value = camera.get("start_timestamp_ms")
            if type(start_value) is not int:
                raise ValueError("provide the measured first-frame time as an integer")
            start = _integer(start_value)
        except ValueError as exc:
            report.errors.append(f"{label}.start_timestamp_ms: {exc}")
            start = None
        sidecar = camera.get("frame_timestamps_path")
        if sidecar is None:
            report.warnings.append(f"{label}: no per-frame timestamps; synchronization is unverified")
            continue
        path = _local_file(session, sidecar, f"{label}.frame_timestamps_path", report)
        interval = _read_times(path, "FRAMES", report) if path else None
        if interval:
            intervals.append(interval)
            if start is not None and start != interval[0]:
                report.errors.append(f"{label}: start_timestamp_ms differs from the first frame timestamp")
            if gps_range:
                if interval[0] > gps_range[1] or gps_range[0] > interval[1]:
                    report.errors.append(f"{label}: frame times do not overlap GPS times")
                elif interval[0] < gps_range[0] or interval[1] > gps_range[1]:
                    report.warnings.append(f"{label}: GPS does not cover the whole video interval")
    if len(intervals) > 1 and max(x[0] for x in intervals) > min(x[1] for x in intervals):
        report.errors.append("camera timestamp intervals do not overlap")
    report.warnings.append("Video decoding, frame count, clock accuracy and frame pairing were not verified")
    report.details["camera_ids"] = sorted(camera_ids)
    return report


def inspect_hdmap(directory: Path) -> ValidationReport:
    """Inventory only: does NOT assign a CRS, map fields, or infer lane semantics."""
    report = ValidationReport()
    if not directory.is_dir():
        report.errors.append("HD Map directory does not exist")
        return report
    files = [path for path in directory.rglob("*") if path.is_file()]
    layers = []
    for path in files:
        suffix = path.suffix.lower()
        if suffix not in (".shp", ".gpkg"):
            continue
        entry = {"path": str(path.relative_to(directory)), "format": suffix[1:]}
        if path.stat().st_size == 0:
            report.errors.append(f"{entry['path']}: empty file")
        if suffix == ".shp":
            siblings = {s.name.lower(): s for s in path.parent.iterdir() if s.is_file()}
            for extension in (".shx", ".dbf", ".prj"):
                companion = siblings.get(path.with_suffix(extension).name.lower())
                if companion is None or companion.stat().st_size == 0:
                    report.errors.append(f"{entry['path']}: missing or empty {extension} companion")
            if path.with_suffix(".cpg").name.lower() not in siblings:
                report.warnings.append(f"{entry['path']}: no .cpg; attribute encoding needs confirmation")
        layers.append(entry)
    report.details["files"] = layers
    if not layers:
        report.errors.append("No .shp or .gpkg files found; extract original archives with companions intact")
    report.warnings.append("CRS, geometry, fields, lane meaning and road coverage still require actual GIS inspection")
    return report
