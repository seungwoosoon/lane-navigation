"""Run from any working directory: python validate_drive.py <session-directory>."""

import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from src.data_validation import validate_drive


def main() -> int:
    parser = argparse.ArgumentParser(description="Check offline drive handoff files (no video decoding)")
    parser.add_argument("session", type=Path)
    args = parser.parse_args()
    report = validate_drive(args.session)
    print(json.dumps(report.as_dict(), ensure_ascii=False, indent=2))
    return 0 if report.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
