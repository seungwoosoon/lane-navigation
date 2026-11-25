"""Prepare a drive from timestamped NMEA RMC data without modifying original media."""

import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from src.gps_preparation import prepare_drive


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert NMEA RMC and associate preceding GPS with recorded frames")
    parser.add_argument("--nmea", required=True, type=Path)
    parser.add_argument("--session", required=True, type=Path)
    parser.add_argument("--max-age-ms", type=int, default=2000,
                        help="initial maximum preceding GPS age, not a validated synchronization tolerance")
    args = parser.parse_args()
    try:
        report = prepare_drive(args.nmea, args.session, args.max_age_ms)
    except (OSError, ValueError, KeyError) as exc:
        parser.exit(1, f"GPS preparation failed: {exc}\n")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
