"""Replay timestamped lane topology, GNSS matches and confirmed camera crossings."""

import argparse
from dataclasses import asdict
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from src.lane_tracking import LaneSnapshot, replay_lane_tracking


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    scenario = json.loads(args.scenario.read_text(encoding="utf-8"))
    snapshots = [
        LaneSnapshot(
            row["gps_second"], tuple(row["lanes_left_to_right"]), row["gps_lane_id"],
            frozenset(row["route_lane_ids"]), row.get("crossing"),
        )
        for row in scenario["snapshots"]
    ]
    successors = {key: tuple(links) for key, links in scenario["successors"].items()}
    replay = replay_lane_tracking(snapshots, successors, scenario["map_lane_numbers"])
    active = [asdict(row) for row in replay if row.gps_second >= scenario["guidance_start_gps_second"]]
    result = {
        "source": str(args.scenario),
        "sample_count": len(active),
        "unknown_count": sum(row["direction"] == "UNKNOWN" for row in active),
        "samples": active,
    }
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(f"Active samples: {result['sample_count']}; UNKNOWN: {result['unknown_count']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
