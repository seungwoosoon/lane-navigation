import unittest

from src.lane_tracking import LaneSnapshot, replay_lane_tracking


class LaneTrackingTests(unittest.TestCase):
    def test_new_left_lane_changes_display_order_before_actual_crossing(self):
        opening = ("pocket", "through")
        replay = replay_lane_tracking(
            [
                LaneSnapshot(0, ("original",), "original", frozenset()),
                LaneSnapshot(1, ("original",), "original", frozenset()),
                LaneSnapshot(2, opening, "pocket", frozenset({"pocket"})),
                LaneSnapshot(3, opening, "through", frozenset({"pocket"}), "LEFT"),
                LaneSnapshot(4, ("next-pocket", "next-through"), None, frozenset({"next-pocket"})),
            ],
            {"original": ("pocket", "through"), "pocket": ("next-pocket",)},
            {"original": "1", "through": "1", "pocket": "91", "next-pocket": "91"},
            initial_votes_required=2,
        )
        self.assertEqual(
            [(None, "UNKNOWN"), (1, "PREPARE"), (2, "LEFT"), (1, "KEEP"), (1, "KEEP")],
            [(row.display_lane, row.direction) for row in replay],
        )
        self.assertEqual(1, replay[2].lane_changes)

    def test_crossing_cannot_supply_an_initial_lane(self):
        replay = replay_lane_tracking(
            [LaneSnapshot(0, ("left", "right"), "right", frozenset({"left"}), "LEFT")],
            {},
            {},
        )
        self.assertEqual("UNKNOWN", replay[0].direction)

    def test_lost_track_does_not_reuse_old_gps_votes(self):
        targets = frozenset({"lane"})
        replay = replay_lane_tracking(
            [
                LaneSnapshot(0, ("lane",), "lane", targets),
                LaneSnapshot(1, ("lane",), "lane", targets),
                LaneSnapshot(8, ("lane",), "lane", targets),
            ],
            {},
            {},
            initial_votes_required=2,
        )
        self.assertEqual(["UNKNOWN", "KEEP", "UNKNOWN"], [row.direction for row in replay])

    def test_camera_ticks_do_not_count_as_new_gps_observations(self):
        targets = frozenset({"lane"})
        replay = replay_lane_tracking(
            [
                LaneSnapshot(0, ("lane",), "lane", targets),
                LaneSnapshot(.2, ("lane",), "lane", targets, gps_observation=False),
                LaneSnapshot(.4, ("lane",), None, targets, gps_observation=False),
                LaneSnapshot(1, ("lane",), "lane", targets),
            ],
            {},
            {},
            initial_votes_required=2,
        )
        self.assertEqual(["UNKNOWN", "UNKNOWN", "UNKNOWN", "KEEP"], [row.direction for row in replay])
