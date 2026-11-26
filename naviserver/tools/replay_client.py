"""폰 없이 서버 + Lane AI 연동을 확인하는 재생 클라이언트 (표준 라이브러리만 사용).

서버의 주행 기록(GET /api/replay)을 받아, 앱처럼 경로를 요청하고 GPS를 차례로 보낸 뒤
안내가 바뀔 때마다 한 줄씩 출력한다.

    py naviserver/tools/replay_client.py                      # 실제 속도
    py naviserver/tools/replay_client.py --speed 5            # 5배 빠르게
    py naviserver/tools/replay_client.py --dest 37.3480,126.6300

먼저 Lane AI 재생 API, 서버(REPLAY_SESSION_DIR 설정)를 띄워 둔다. README의 '전체 시스템 실행' 참고.
"""
import argparse
import json
import time
import urllib.request


def call(url, body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"},
                                 method="GET" if body is None else "POST")
    with urllib.request.urlopen(req, timeout=10) as res:
        return json.loads(res.read().decode("utf-8"))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--server", default="http://127.0.0.1:8080")
    ap.add_argument("--speed", type=float, default=1.0, help="재생 속도 배율")
    ap.add_argument("--dest", help="목적지 'lat,lng' (기본: 기록의 마지막 위치)")
    args = ap.parse_args()
    base = args.server.rstrip("/")

    track = call(base + "/api/replay")
    points = track.get("points") or []
    if not track.get("sessionId") or not points:
        print("서버에 재생할 주행 기록이 없습니다. 서버의 REPLAY_SESSION_DIR(navi.replay.session-dir)을 확인하세요.")
        return 1
    if args.dest:
        lat, lng = (float(v) for v in args.dest.split(","))
        dest = {"lat": lat, "lng": lng}
    else:
        dest = {"lat": points[-1]["lat"], "lng": points[-1]["lng"]}
    print(f"세션 {track['sessionId']}: GPS {len(points)}개, 목적지 {dest['lat']:.6f},{dest['lng']:.6f}")

    route = call(base + "/api/route", {"start": {"lat": points[0]["lat"], "lng": points[0]["lng"]}, "end": dest})
    print(f"경로 {route['routeId']}: 점 {len(route['path'])}개")

    start_ms = track.get("recordingStartMs") or points[0]["timestampMs"]
    last = None
    for i, p in enumerate(points):
        nav = call(base + "/api/location", {"routeId": route["routeId"], "lat": p["lat"], "lng": p["lng"],
                                            "speed": p.get("speed") or 0.0, "timestampMs": p["timestampMs"]})
        key = (nav.get("guide"), nav.get("maneuver"), nav.get("totalLanes"), nav.get("currentLane"),
               tuple(nav.get("targetLanes") or ()), nav.get("currentLaneSource"), nav.get("arrived"))
        if key != last:
            t = (p["timestampMs"] - start_ms) / 1000
            if nav.get("arrived"):
                print(f"[{t:7.1f}s] 도착")
            elif nav.get("guide"):
                print(f"[{t:7.1f}s] {nav.get('distanceToBranch')}m {nav.get('maneuver')} 차로 {nav.get('totalLanes')} "
                      f"목표 {nav.get('targetLanes')} 현재 {nav.get('currentLane')}({nav.get('currentLaneSource')}) "
                      f"화살표 {nav.get('laneDirections')} | {nav.get('message')}")
            else:
                print(f"[{t:7.1f}s] 안내 없음")
            last = key
        if nav.get("arrived"):
            break
        if i + 1 < len(points):
            time.sleep(max(0.0, (points[i + 1]["timestampMs"] - p["timestampMs"]) / 1000 / args.speed))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
