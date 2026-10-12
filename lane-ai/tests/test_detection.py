"""Synthetic tensors only. These are decoder tests, NOT accuracy evaluations."""

from pathlib import Path
import tempfile
import unittest

try:
    import numpy as np
    import torch
    import torchvision
    INFERENCE_AVAILABLE = True
except ImportError:
    INFERENCE_AVAILABLE = False

if INFERENCE_AVAILABLE:
    from src.detection import EXPECTED_SHAPES, UfldDetector, decode_predictions, preprocess


@unittest.skipUnless(INFERENCE_AVAILABLE, "install requirements-inference.txt for inference tests")
class DecoderTests(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(2)
        self.prediction = {name: torch.zeros(shape) for name, shape in EXPECTED_SHAPES.items()}

    def test_absent_boundaries_have_no_coordinates_or_quality(self):
        result = decode_predictions(self.prediction, 640, 480)
        self.assertEqual([x.boundary_id for x in result], ["slot_0", "slot_1", "slot_2", "slot_3"])
        self.assertTrue(all(not x.valid and not x.points_xy and x.quality is None for x in result))

    def test_row_peak_decodes_into_original_image_pixels(self):
        self.prediction["exist_row"][0, 1, :, 1] = 10
        self.prediction["loc_row"][0, 99, :, 1] = 30
        boundary = decode_predictions(self.prediction, 640, 480)[1]
        self.assertTrue(boundary.valid)
        self.assertEqual(len(boundary.points_xy), 72)
        self.assertAlmostEqual(boundary.points_xy[0][0], 320, places=3)
        self.assertAlmostEqual(boundary.points_xy[0][1], 0.42 * 480, places=3)
        self.assertEqual(boundary.points_xy[-1][1], 479)

    def test_column_peak_is_y_and_anchor_is_x(self):
        self.prediction["exist_col"][0, 1, :, 0] = 10
        self.prediction["loc_col"][0, 49, :, 0] = 30
        boundary = decode_predictions(self.prediction, 640, 480)[0]
        self.assertEqual(len(boundary.points_xy), 81)
        self.assertAlmostEqual(boundary.points_xy[0][1], 240, places=3)
        self.assertEqual(boundary.points_xy[0][0], 0)
        self.assertEqual(boundary.points_xy[-1][0], 639)

    def test_official_row_presence_threshold_is_strict(self):
        self.prediction["exist_row"][0, 1, :36, 1] = 10
        self.assertFalse(decode_predictions(self.prediction, 640, 480)[1].valid)
        self.prediction["exist_row"][0, 1, 36, 1] = 10
        self.assertTrue(decode_predictions(self.prediction, 640, 480)[1].valid)

    def test_nonfinite_logits_and_wrong_profile_are_rejected(self):
        self.prediction["loc_row"][0, 0, 0, 0] = float("nan")
        with self.assertRaises(ValueError):
            decode_predictions(self.prediction, 640, 480)
        self.prediction["loc_row"] = torch.zeros(1, 2, 2, 2)
        with self.assertRaises(ValueError):
            decode_predictions(self.prediction, 640, 480)

    def test_preprocessing_uses_rgb_imagenet_normalization(self):
        frame = np.zeros((40, 80, 3), dtype=np.uint8)
        frame[:, :, 2] = 255  # red in OpenCV BGR
        result = preprocess(frame)
        self.assertEqual(tuple(result.shape), (1, 3, 320, 1600))
        self.assertAlmostEqual(float(result[0, 0, 0, 0]), (1 - 0.485) / 0.229, places=5)
        self.assertAlmostEqual(float(result[0, 2, 0, 0]), -0.406 / 0.225, places=5)

    def test_preprocessing_matches_bottom_crop(self):
        # Top 40% red, bottom 60% blue; all but crop boundary should be blue.
        frame = np.zeros((533, 1600, 3), dtype=np.uint8)
        frame[:213, :, 2] = 255
        frame[213:, :, 0] = 255
        result = preprocess(frame)
        self.assertAlmostEqual(float(result[0, 2, -1, 0]), (1 - 0.406) / 0.225, places=5)

    def test_missing_checkpoint_never_falls_back_to_random_model(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                UfldDetector(Path(directory) / "absent.pth")

    def test_wrong_checkpoint_keys_are_not_silently_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.pth"
            torch.save({"model": {"wrong": torch.tensor([1.0])}}, path)
            with self.assertRaises(RuntimeError):
                UfldDetector(path)


if __name__ == "__main__":
    unittest.main()
