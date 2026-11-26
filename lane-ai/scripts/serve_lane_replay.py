"""HTTP adapter for the recorded approach integration test."""

import argparse
from bisect import bisect_right
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path


class Replay:
    def __init__(self, result: dict, recording: dict):
        if result["session_id"] != recording["session_id"] or recording["timebase"] != "unix_ms":
            raise ValueError("Result does not match the recording metadata")
        if not result["route_link"]:
            raise ValueError("The maneuver replay must be analyzed with --route-link")
        origin = min(camera["start_timestamp_ms"] for camera in recording["cameras"])
        self.session_id = result["session_id"]
        self.route_link = result["route_link"]
        self.numbering = result["lane_numbering"]
        self.period_ms = 1000 / result["sample_fps"]
        self.samples = tuple(result["samples"])
        self.times = tuple(row["timestamp_ms"] - origin for row in self.samples)
        if not self.times or any(a >= b for a, b in zip(self.times, self.times[1:])):
            raise ValueError("Replay requires strictly increasing result timestamps")

    def lookup(self, elapsed_ms: int) -> dict:
        index = bisect_right(self.times, elapsed_ms) - 1
        sample = None
        reason = None
        if index < 0 or elapsed_ms >= self.times[-1] + self.period_ms:
            reason = "out_of_replay_range"
        elif elapsed_ms - self.times[index] > 2 * self.period_ms:
            reason = "stale_replay_sample"
        else:
            sample = self.samples[index]
            if sample["status"] != "VALID":
                reason = sample["evidence"]
        valid = sample is not None and sample["status"] == "VALID"
        return {
            "mode": "REPLAY",
            "sessionId": self.session_id,
            "maneuverLinkId": self.route_link,
            "recordingElapsedMs": elapsed_ms,
            "analyzedElapsedMs": self.times[index] if sample is not None else None,
            "timestampMs": sample["timestamp_ms"] if sample is not None else None,
            "laneNumbering": self.numbering,
            "status": "VALID" if valid else "UNKNOWN",
            "currentLane": sample["current_lane"] if valid else None,
            "mapLinkId": sample["map_link_id"] if valid else None,
            "targetLane": sample["target_lane"] if valid else None,
            "movement": sample["movement"] if valid else None,
            "laneChanges": sample["lane_changes"] if valid else None,
            "reason": reason,
        }


def make_handler(replay: Replay) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        def send_json(self, status: HTTPStatus, response: dict) -> None:
            data = json.dumps(response, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self) -> None:
            if self.path != "/health":
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
                return
            self.send_json(HTTPStatus.OK, {
                "mode": "REPLAY", "sessionId": replay.session_id, "maneuverLinkId": replay.route_link,
            })

        def do_POST(self) -> None:
            if self.path != "/v1/replay/lane-estimate":
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 4096:
                    raise ValueError("Invalid request size")
                request = json.loads(self.rfile.read(length))
            except (ValueError, UnicodeError):
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_json_request"})
                return
            if (
                not isinstance(request, dict)
                or not isinstance(request.get("sessionId"), str)
                or not isinstance(request.get("maneuverLinkId"), str)
                or type(request.get("recordingElapsedMs")) is not int
                or request["recordingElapsedMs"] < 0
            ):
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_replay_fields"})
                return
            if request["sessionId"] != replay.session_id or request["maneuverLinkId"] != replay.route_link:
                self.send_json(HTTPStatus.CONFLICT, {"error": "replay_session_or_maneuver_mismatch"})
                return
            self.send_json(HTTPStatus.OK, replay.lookup(request["recordingElapsedMs"]))

    return Handler


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--result", required=True, type=Path)
    parser.add_argument("--recording", required=True, type=Path)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    replay = Replay(json.loads(args.result.read_text(encoding="utf-8-sig")),
                    json.loads(args.recording.read_text(encoding="utf-8-sig")))
    with ThreadingHTTPServer((args.host, args.port), make_handler(replay)) as httpd:
        print(f"Lane AI replay API on http://{args.host}:{httpd.server_port}", flush=True)
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
