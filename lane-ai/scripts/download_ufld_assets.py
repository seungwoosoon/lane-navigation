"""Explicit download of official assets; never invoked by the inference module."""

import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
COMMIT = "c903880678454dfd9b55a63022368db05c00bc6d"
WEIGHTS_URL = "https://drive.google.com/file/d/1oEjJraFr-3lxhX_OXduAGFWalWa6Xh3W/view"
SAMPLE_URL = f"https://raw.githubusercontent.com/cfzd/Ultra-Fast-Lane-Detection-v2/{COMMIT}/example.mp4"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description="Download official UFLD v2 weights/sample (large files, Git ignored)")
    parser.add_argument("--weights", action="store_true")
    parser.add_argument("--sample", action="store_true")
    args = parser.parse_args()
    if not (args.weights or args.sample):
        parser.error("choose --weights and/or --sample")
    for enabled, url, destination in (
        (args.weights, WEIGHTS_URL, ROOT / "models/weights/culane_res18.pth"),
        (args.sample, SAMPLE_URL, ROOT / "data/sample/ufld_example.mp4"),
    ):
        if not enabled:
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            raise FileExistsError(f"will not overwrite {destination}")
        partial = destination.with_suffix(destination.suffix + ".part")
        if partial.exists():
            raise FileExistsError(f"partial download already exists: {partial}; inspect it before retrying")
        if destination.suffix == ".pth":
            import gdown
            result = gdown.download(url, str(partial), fuzzy=True, quiet=False)
            if result is None:
                raise RuntimeError("Google Drive download failed; use the official README link manually")
        else:
            urllib.request.urlretrieve(url, partial)
        if not partial.is_file() or partial.stat().st_size == 0:
            raise RuntimeError("download returned no bytes")
        partial.rename(destination)
        provenance = {"url": url, "upstream_commit": COMMIT, "bytes": destination.stat().st_size,
                      "sha256": sha256(destination), "hash_source": "computed locally, not upstream attestation"}
        destination.with_suffix(destination.suffix + ".source.json").write_text(
            json.dumps(provenance, indent=2), encoding="utf-8")
        print(json.dumps({"saved": str(destination), **provenance}, indent=2))


if __name__ == "__main__":
    main()
