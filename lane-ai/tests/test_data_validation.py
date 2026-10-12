"""All fixtures here are SYNTHETIC. Video/map bytes are not real media or GIS."""

import json
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest

from src.data_validation import inspect_hdmap, validate_drive


class DriveValidationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.start = 1700000000000
        self.manifest = {"schema_version": 1, "session_id": "synthetic-test", "timebase": "unix_ms",
                         "gps_path": "gps.csv", "cameras": []}
        for camera in ("left", "right"):
            (self.root / f"{camera}.mp4").write_bytes(b"SYNTHETIC FILE: NOT A VIDEO")
            (self.root / f"{camera}_frames.csv").write_text(
                f"frame_id,timestamp_ms\n0,{self.start}\n1,{self.start + 1000}\n", encoding="utf-8")
            self.manifest["cameras"].append({"camera_id": camera, "video_path": f"{camera}.mp4",
                "start_timestamp_ms": self.start, "frame_timestamps_path": f"{camera}_frames.csv"})
        self.write_gps()

    def write_gps(self, rows=None):
        if rows is None:
            rows = f"{self.start},37.4,126.6,,,\n{self.start + 1000},37.4,126.6,4.2,8.3,92\n"
        (self.root / "gps.csv").write_text(
            "timestamp_ms,latitude,longitude,accuracy_m,speed_mps,bearing_deg\n" + rows, encoding="utf-8-sig")

    def validate(self):
        (self.root / "recording.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        return validate_drive(self.root)

    def test_valid_package_still_reports_unverified_decoding(self):
        report = self.validate()
        self.assertTrue(report.ok, report.errors)
        self.assertTrue(any("decoding" in message for message in report.warnings))

    def test_missing_manifest_is_a_report_not_exception(self):
        self.assertFalse(validate_drive(self.root).ok)

    def test_template_null_time_is_rejected(self):
        self.manifest["cameras"][0]["start_timestamp_ms"] = None
        self.assertFalse(self.validate().ok)

    def test_missing_video_is_rejected(self):
        (self.root / "left.mp4").unlink()
        self.assertFalse(self.validate().ok)

    def test_paths_cannot_escape_session(self):
        self.manifest["gps_path"] = "../gps.csv"
        self.assertTrue(any("inside" in error for error in self.validate().errors))

    def test_gps_coordinates_and_optional_numbers_must_be_valid(self):
        for value in ("91,126.6,4,8,92", "37.4,181,4,8,92", "NaN,126.6,4,8,92",
                      "37.4,126.6,-1,8,92", "37.4,126.6,4,inf,92", "37.4,126.6,4,8,360"):
            with self.subTest(value=value):
                self.write_gps(f"{self.start},{value}\n")
                self.assertFalse(self.validate().ok)

    def test_duplicate_or_backward_gps_times_rejected(self):
        for second in (self.start, self.start - 1):
            with self.subTest(second=second):
                self.write_gps(f"{self.start},37.4,126.6,,,\n{second},37.4,126.6,,,\n")
                self.assertFalse(self.validate().ok)

    def test_gps_extra_columns_rejected(self):
        self.write_gps(f"{self.start},37.4,126.6,,,,extra\n")
        self.assertFalse(self.validate().ok)

    def test_camera_ids_must_be_unique(self):
        self.manifest["cameras"][1]["camera_id"] = "left"
        self.assertFalse(self.validate().ok)

    def test_missing_frame_times_warns_instead_of_claiming_sync(self):
        self.manifest["cameras"][0]["frame_timestamps_path"] = None
        report = self.validate()
        self.assertTrue(report.ok, report.errors)
        self.assertTrue(any("synchronization is unverified" in warning for warning in report.warnings))

    def test_non_overlapping_gps_and_camera_rejected(self):
        self.write_gps(f"{self.start + 10000},37.4,126.6,,,\n{self.start + 11000},37.4,126.6,,,\n")
        self.assertTrue(any("do not overlap GPS" in error for error in self.validate().errors))

    def test_first_frame_must_match_metadata(self):
        self.manifest["cameras"][0]["start_timestamp_ms"] += 1
        self.assertFalse(self.validate().ok)

    def test_disjoint_cameras_rejected_even_if_gps_spans_both(self):
        self.write_gps(f"{self.start},37.4,126.6,,,\n{self.start + 10000},37.4,126.6,,,\n")
        self.manifest["cameras"][1]["start_timestamp_ms"] += 5000
        (self.root / "right_frames.csv").write_text(
            f"frame_id,timestamp_ms\n0,{self.start + 5000}\n1,{self.start + 6000}\n", encoding="utf-8")
        self.assertTrue(any("camera timestamp intervals" in error for error in self.validate().errors))

    def test_label_files_are_not_read_as_inference_input(self):
        (self.root / "labels.csv").write_text("not even a valid label file", encoding="utf-8")
        self.assertTrue(self.validate().ok)

    def test_cli_json_and_exit_status_from_other_working_directory(self):
        self.validate()
        script = Path(__file__).resolve().parents[1] / "scripts" / "validate_drive.py"
        for invalid in (False, True):
            with self.subTest(invalid=invalid):
                if invalid:
                    (self.root / "left.mp4").unlink()
                result = subprocess.run([sys.executable, str(script), str(self.root)],
                    cwd=self.root, capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode, 1 if invalid else 0, result.stderr)
                self.assertEqual(json.loads(result.stdout)["ok"], not invalid)

    def test_invalid_path_is_a_report_not_exception(self):
        self.manifest["gps_path"] = "bad\u0000path.csv"
        self.assertFalse(self.validate().ok)


class MapInventoryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def test_shapefile_requires_companions(self):
        (self.root / "lanes.shp").write_bytes(b"SYNTHETIC NOT GIS")
        report = inspect_hdmap(self.root)
        self.assertEqual(len(report.errors), 3)

    def test_complete_file_set_does_not_claim_valid_geometry(self):
        for extension in ("shp", "shx", "dbf", "prj"):
            (self.root / f"lanes.{extension}").write_bytes(b"SYNTHETIC NOT GIS")
        report = inspect_hdmap(self.root)
        self.assertTrue(report.ok)
        self.assertTrue(any("actual GIS inspection" in warning for warning in report.warnings))
        self.assertTrue(any(".cpg" in warning for warning in report.warnings))

    def test_archive_only_is_not_a_parsed_map(self):
        (self.root / "map.zip").write_bytes(b"SYNTHETIC NOT ZIP")
        self.assertFalse(inspect_hdmap(self.root).ok)

    def test_empty_geopackage_rejected(self):
        (self.root / "map.gpkg").touch()
        self.assertFalse(inspect_hdmap(self.root).ok)


if __name__ == "__main__":
    unittest.main()
