"""Check map package completeness; this is not a GIS layer parser."""

import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from src.data_validation import inspect_hdmap


def main() -> int:
    parser = argparse.ArgumentParser(description="Inventory Shapefile/GeoPackage handoff files")
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    report = inspect_hdmap(args.directory)
    print(json.dumps(report.as_dict(), ensure_ascii=False, indent=2))
    return 0 if report.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
