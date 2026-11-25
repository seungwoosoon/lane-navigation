import unittest

from src.contracts import LaneEstimate
from src.lane_guidance import LaneGuidance, plan_lane_guidance


class LaneGuidanceTests(unittest.TestCase):
    def test_physical_order_supports_non_sequential_lane_numbers(self):
        ordered = ("special", "left", "middle", "right")
        cases = [
            ("right", 3, {"left"}, LaneGuidance("LEFT", 2, "left")),
            ("special", 91, {"left"}, LaneGuidance("RIGHT", 1, "left")),
            ("left", 1, {"special", "left"}, LaneGuidance("KEEP", 0, "left")),
            ("left", 1, {"special", "middle"}, LaneGuidance("UNKNOWN", None, None)),
        ]
        for current, number, targets, expected in cases:
            with self.subTest(current=current, targets=targets):
                estimate = LaneEstimate(1, "approach", current, number, 1, "VALID", "test fixture")
                self.assertEqual(expected, plan_lane_guidance(estimate, "approach", ordered, targets))

    def test_unknown_estimate_or_different_approach_returns_no_instruction(self):
        estimates = (
            LaneEstimate(1, "approach", None, None, None, "UNKNOWN"),
            LaneEstimate(1, "other", "right", 2, 1, "VALID", "test fixture"),
        )
        for estimate in estimates:
            with self.subTest(estimate=estimate):
                result = plan_lane_guidance(estimate, "approach", ("left", "right"), {"left"})
                self.assertEqual(LaneGuidance("UNKNOWN", None, None), result)
