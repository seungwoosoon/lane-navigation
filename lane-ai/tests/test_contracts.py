"""Output invariants only; these tests do not simulate an inference model."""

import unittest

from src.contracts import LaneEstimate


class LaneEstimateTests(unittest.TestCase):
    def test_unknown_can_have_no_confidence(self):
        estimate = LaneEstimate(1700000000000, None, None, None, None, "UNKNOWN", reason="no evidence")
        self.assertIsNone(estimate.lane_id)

    def test_unknown_must_not_assign_a_lane(self):
        with self.assertRaises(ValueError):
            LaneEstimate(1700000000000, "road", "lane", 2, None, "UNKNOWN")

    def test_valid_requires_identity_and_confidence(self):
        with self.assertRaises(ValueError):
            LaneEstimate(1700000000000, "road", "lane", 2, None, "VALID")

    def test_confidence_requires_documented_method(self):
        with self.assertRaises(ValueError):
            LaneEstimate(1700000000000, "road", "lane", 2, 0.8, "VALID")

    def test_nonfinite_or_out_of_range_confidence_rejected(self):
        for score in (float("nan"), float("inf"), -0.1, 1.1, True):
            with self.subTest(score=score), self.assertRaises(ValueError):
                LaneEstimate(1700000000000, "road", "lane", 2, score, "VALID", "synthetic-test-only")


if __name__ == "__main__":
    unittest.main()
