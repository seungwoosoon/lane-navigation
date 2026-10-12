"""Generated flat-color videos only; no synthetic detector in production code."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

try:
    import cv2
    import numpy as np
    import torch
    import torchvision
    INFERENCE_AVAILABLE = True
except ImportError:
    INFERENCE_AVAILABLE = False

if INFERENCE_AVAILABLE:
    from src.contracts import CameraObservation
    from src.detection_runner import run_detection
    from src.video_reader import VideoReader, read_frame_times


@unittest.skipUnless(INFERENCE_AVAILABLE, "install requirements-inference.txt for video tests")
class VideoTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.video = self.root / "synthetic.mp4"
        writer = cv2.VideoWriter(str(self.video), cv2.VideoWriter_fourcc(*"mp4v"), 10, (64, 48))
        self.assertTrue(writer.isOpened())
        for color in (0, 80, 160):
            writer.write(np.full((48, 64, 3), color, dtype=np.uint8))
        writer.release()

    def test_relative_time_does_not_invent_utc(self):
        with VideoReader(self.video) as reader:
            frames = list(reader.frames())
        self.assertEqual(len(frames), 3)
        self.assertTrue(all(x.timestamp_ms is None for x in frames))
        self.assertTrue(all("UTC_unavailable" in x.timestamp_source for x in frames))

    def test_measured_frame_times_are_preserved(self):
        times = self.root / "frames.csv"
        times.write_text("frame_id,timestamp_ms\n0,1700000000000\n1,1700000000112\n2,1700000000195\n")
        with VideoReader(self.video, timestamps=times) as reader:
            frames = list(reader.frames())
        self.assertEqual(frames[1].timestamp_ms, 1700000000112)
        self.assertEqual(frames[1].timestamp_source, "frame_timestamp_csv")

    def test_missing_frame_time_is_an_error_not_a_guess(self):
        times = self.root / "frames.csv"
        times.write_text("frame_id,timestamp_ms\n0,1700000000000\n")
        with VideoReader(self.video, timestamps=times) as reader, self.assertRaises(ValueError):
            list(reader.frames())

    def test_duplicate_times_rejected(self):
        times = self.root / "frames.csv"
        times.write_text("frame_id,timestamp_ms\n0,100\n1,100\n")
        with self.assertRaises(ValueError):
            read_frame_times(times)

    def test_container_start_offset_does_not_shift_measured_start(self):
        with patch("src.video_reader.cv2.VideoCapture") as capture_class:
            capture = capture_class.return_value
            raw_times = iter((500.0, 600.0))
            capture.isOpened.return_value = True
            capture.get.side_effect = lambda prop: (
                10.0 if prop == cv2.CAP_PROP_FPS else
                2.0 if prop == cv2.CAP_PROP_FRAME_COUNT else next(raw_times))
            capture.read.side_effect = [(True, np.zeros((48, 64, 3), dtype=np.uint8))] * 2 + [(False, None)]
            with VideoReader(self.video, start_timestamp_ms=1700000000000) as reader:
                frames = list(reader.frames())
        self.assertEqual([x.video_time_ms for x in frames], [0, 100])
        self.assertEqual([x.timestamp_ms for x in frames], [1700000000000, 1700000000100])

    def test_explicit_start_is_marked_unverified(self):
        with VideoReader(self.video, start_timestamp_ms=1700000000000) as reader:
            frames = list(reader.frames(2))
        self.assertEqual(len(frames), 2)
        self.assertEqual(frames[0].timestamp_ms, 1700000000000)
        self.assertIn("clock_alignment_unverified", frames[1].timestamp_source)

    def test_runner_writes_real_video_and_json_with_test_only_stub(self):
        # Isolate file I/O from the large network. Real network is smoke-tested separately.
        weights = self.root / "synthetic-weights.pth"
        weights.write_bytes(b"TEST ONLY")
        output = self.root / "results"
        with patch("src.detection_runner.UfldDetector") as detector_class:
            detector_class.return_value.detect.side_effect = lambda image, cam, idx, stamp, rel, source: (
                CameraObservation(cam, idx, stamp, 64, 48, (), rel, source))
            summary = run_detection(self.video, weights, output, max_frames=2)
        self.assertEqual(summary["processed_frames"], 2)
        records = [json.loads(line) for line in (output / "boundaries.jsonl").read_text().splitlines()]
        self.assertEqual([row["frame_id"] for row in records], [0, 1])
        self.assertIsNone(records[0]["timestamp_ms"])
        capture = cv2.VideoCapture(str(output / "overlay.mp4"))
        try:
            self.assertEqual(int(capture.get(cv2.CAP_PROP_FRAME_COUNT)), 2)
        finally:
            capture.release()
        self.assertTrue((output / "summary.json").is_file())
        with self.assertRaises(FileExistsError):
            run_detection(self.video, weights, output)


if __name__ == "__main__":
    unittest.main()
