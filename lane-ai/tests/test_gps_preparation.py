"""Synthetic NMEA/clock fixtures, not real road or model accuracy tests."""

from datetime import datetime, timezone
from pathlib import Path
import tempfile
import unittest

from src.gps_preparation import align_frames, parse_nmea


def sentence(body):
    checksum = 0
    for char in body:
        checksum ^= ord(char)
    return f"${body}*{checksum:02X}\n"


class GpsPreparationTests(unittest.TestCase):
    def parse(self, content):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.nmea"
            path.write_text(content, encoding="utf-8")
            return parse_nmea(path)

    def test_utc_coordinate_units_and_negative_speed(self):
        rows, report = self.parse(sentence("GPRMC,082333.99,A,3720.0000,N,12630.0000,E,-1.9,184.9,241126,,,A"))
        expected = datetime(2026, 11, 24, 8, 23, 33, 990000, tzinfo=timezone.utc)
        self.assertEqual(rows[0]["timestamp_ms"], round(expected.timestamp() * 1000))
        self.assertAlmostEqual(rows[0]["latitude"], 37 + 20 / 60)
        self.assertAlmostEqual(rows[0]["longitude"], 126.5)
        self.assertIsNone(rows[0]["speed_mps"])
        self.assertEqual(report["counts"]["invalid_speed_blank"], 1)

    def test_invalid_checksum_and_inactive_positions_are_excluded(self):
        valid = sentence("GNRMC,082335,A,3720.0000,N,12630.0000,E,10,90,241126,,,A")
        inactive = sentence("GNRMC,082334,V,3720.0000,N,12630.0000,E,0,0,241126,,,A")
        rows, report = self.parse("$GPRMC,broken*XX\n" + inactive + valid)
        self.assertEqual(len(rows), 1)
        self.assertAlmostEqual(rows[0]["speed_mps"], 10 * 1852 / 3600)
        self.assertEqual(report["counts"]["invalid_sentences"], 1)
        self.assertEqual(report["counts"]["inactive_rmc"], 1)

    def test_mapping_never_uses_future_gps_or_shifts_start(self):
        gps = [{"timestamp_ms": t, "latitude": 37., "longitude": 126., "speed_mps": None, "bearing_deg": None}
               for t in (4000, 5000, 10000)]
        rows = list(align_frames({0: 1000, 1: 4900, 2: 8000, 3: 11000}, gps, 2000))
        self.assertEqual([r["status"] for r in rows], ["before_gps_start", "matched", "gps_too_old", "after_gps_end"])
        self.assertEqual(rows[1]["gps_timestamp_ms"], 4000)
        self.assertEqual(rows[1]["gps_age_ms"], 900)
        self.assertIsNone(rows[0]["latitude"])
        self.assertIsNone(rows[2]["latitude"])

    def test_backwards_or_duplicate_timestamps_require_inspection(self):
        row = sentence("GPRMC,082333,A,3720.0000,N,12630.0000,E,0,0,241126,,,A")
        with self.assertRaisesRegex(ValueError, "increase strictly"):
            self.parse(row + row)


if __name__ == "__main__":
    unittest.main()
