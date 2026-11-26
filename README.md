# LaneNavigation

> 차로 단위로 안내하는 내비게이션

일반 내비게이션은 "우회전하세요"까지만 알려줍니다. LaneNavigation은 카메라 AI로 현재 차로를 인식하고 정밀도로지도에서 목표 차로를 조회해, **"오른쪽으로 2차로 이동하세요"** 까지 안내합니다.

`인하대학교 공간정보공학 종합설계` · `2026.09 ~` · `3인 팀`

<br/>

## 주요 기능

- **실시간 위치 추적** — GPS로 현재 주행 중인 도로를 지도에 표시
- **경로 안내** — 출발지·목적지를 받아 경로를 계산하고 지도에 표시
- **차로 안내** — 현재 차로와 목표 차로의 차이를 계산해 이동 방향과 횟수를 안내
- **AI 선택 실행** — 분기점까지의 거리와 주행 속도를 기준으로 필요한 순간에만 카메라 AI를 실행

<br/>

## 기술 스택

| 영역 | 사용 기술 |
|---|---|
| Backend | Java 17 · Spring Boot 3.5 · Gradle |
| Database | PostgreSQL · PostGIS |
| Android | Kotlin · 카카오맵 SDK · Retrofit · FusedLocationProvider |
| AI | Python |

<br/>

## 프로젝트 구조

```
lane-navigation/
├── naviserver/     Spring Boot 서버 — 경로 탐색, 차로 정보 제공
├── MyNavi/         Android 앱 — 지도 표시, GPS 수집, 안내 출력
└── lane-ai/        차로 인식 모델 — 영상에서 좌우 차로 수 인식
```

<br/>

## 시작하기

### 서버 실행

```bash
cd naviserver
./gradlew bootRun
```

`http://localhost:8080` 에서 실행됩니다.

### 앱 실행

1. Android Studio에서 `MyNavi` 폴더를 엽니다
2. `MyNavi/local.properties` 에 서버 주소와 카카오맵 키를 적습니다 (git에 올라가지 않는 파일)

   ```properties
   API_BASE_URL=http://<서버 IP>:8080/
   KAKAO_NATIVE_KEY=<카카오 네이티브 앱 키>
   ```

3. 빌드합니다. 폰은 서버와 같은 네트워크(예: 같은 핫스팟)에 연결되어 있어야 합니다

### 데이터베이스

PostgreSQL에 PostGIS·pgRouting 확장을 설치한 뒤, 국토지리정보원 정밀도로지도(EPSG:5179)를 적재하고 길찾기 그래프를 만듭니다.

```sql
CREATE EXTENSION postgis;
CREATE EXTENSION pgrouting;
```

시험 지역을 추가할 때는 받은 정밀도로지도 zip(또는 shp 폴더)을 SQL로 바꿔 넣고, 그래프를 다시 만듭니다.
같은 id는 건너뛰므로 여러 번 실행해도 됩니다.

```powershell
py -m pip install pyshp
py naviserver/sql/tools/hdmap_to_sql.py "C:\path\B110_SEC001_2023.zip" -o hdmap_b110.sql
psql -U postgres -d lanenavi -f hdmap_b110.sql
psql -U postgres -d lanenavi -f naviserver/sql/01_route_graph.sql
```

<br/>

## 전체 시스템 실행 (주행 기록 재생)

실시간 카메라 입력 전 단계라, 녹화한 주행(GPS + Lane AI 분석 결과)을 재생해 **앱 → 서버 → 정밀지도 DB → Lane AI → 앱** 전체를 돌립니다.
AI 담당에게 받을 파일: 세션 폴더(`recording.json`, `gps.csv`)와 분석 결과(`unified_uturn_final.json` 등). 자세한 AI API는
[유턴 재생 연동 안내](lane-ai/docs/server-replay-integration.md)를 봅니다.

받은 세 파일(`recording.json`, `gps.csv`, `unified_uturn_final.json`)은 `lane-ai\data\drives\<세션 이름>\` 폴더에 둡니다
(git에 올라가지 않는 폴더). 아래 예시는 11/24 유턴 주행 `drive_20261124_172325`입니다.

1. **Lane AI 재생 API** (같은 노트북, 포트 8765)

   ```powershell
   cd C:\lane-navigation\lane-ai\data\drives\drive_20261124_172325
   py C:\lane-navigation\lane-ai\scripts\serve_lane_replay.py --result unified_uturn_final.json --recording recording.json
   ```

2. **서버**: IntelliJ 실행 설정의 환경변수에 아래를 추가하고 실행합니다.

   | 환경변수 | 값 |
   |---|---|
   | `DB_PASSWORD`, `KAKAO_REST_KEY` | 기존과 같음 |
   | `REPLAY_SESSION_DIR` | 세션 폴더, 예: `C:/lane-navigation/lane-ai/data/drives/drive_20261124_172325` |
   | `REPLAY_FROM_SEC`, `REPLAY_TO_SEC` | 재생 구간(녹화 시작부터 초). 이 주행은 `250`, `400` |
   | `LANE_AI_URL` | 기본 `http://127.0.0.1:8765` (다르면 지정) |

   재생 구간을 정하면 그 구간의 첫 위치가 출발지, 마지막 위치가 목적지가 됩니다. 이 주행 기록은 출발지로 되돌아오는 한 바퀴라
   구간 없이 전체를 재생하면 목적지가 출발지 옆이 되어 경로에 유턴이 들어가지 않습니다. 250~400초는 유턴 약 500m 앞에서 출발해
   유턴 후 반대 차로에서 끝납니다 (AI 분석 결과는 288~308초).

3. **폰 없이 확인**: 서버가 받는 그대로 GPS를 보내고 안내를 출력합니다.

   ```powershell
   py naviserver\tools\replay_client.py
   ```

   이 주행이면 아래처럼 나옵니다 (초 = 녹화 시작부터). 이렇게 바뀌면 전체 연결 성공입니다.

   ```
   [  272.8s] 291m UTURN 차로 5 목표 [1] 현재 None(None) … | 291m 앞 유턴. 1차로로 가세요.
   [  288.8s] 111m UTURN 차로 5 목표 [1] 현재 2(AI) … | 111m 앞 유턴. 현재 2차로, 왼쪽으로 1차로 이동하세요.
   [  306.8s] 57m UTURN 차로 5 목표 [1] 현재 1(AI) … | 57m 앞 유턴. 현재 차로를 유지하세요.
   [  312.8s] 안내 없음
   [  395.8s] 도착
   ```

   AI는 지금 달리는 정밀지도 링크도 같이 주고, 서버는 그 링크가 이어지는 단면(유턴 포켓 포함 5차로)의 번호로 바꿉니다.
   그래서 포켓이 생기기 전(288초, AI는 1차로)도 서버 기준으로는 2차로입니다. AI 분석이 끝난 뒤(308초~)에는 80m 안에서 마지막 차로를 이어 씁니다.

4. **앱**: `MyNavi/local.properties`에 아래를 넣고 빌드합니다. 앱이 서버의 주행 기록을 받아 첫 위치에 차를 두고
   목적지 칸에 마지막 위치를 채웁니다. **안내 시작**을 누르면 기록대로 달리며 차로 안내가 나옵니다.

   ```properties
   REPLAY_MODE=true
   REPLAY_SPEED=1
   ```

   실제 주행으로 돌아갈 때는 `REPLAY_MODE=false`(또는 삭제)로 다시 빌드합니다.

카카오 경로가 다른 유턴 지점을 고르면 서버가 고른 회전 링크가 AI 자료(`A223AA160179`)와 달라 AI가 409를 돌려줍니다. 이때와 AI가 꺼져 있을 때 서버는 현재 차로 없이 "1차로로 가세요"처럼 목표 차로만 안내합니다.

<br/>

## API

### `POST /api/route` — 경로 만들기 (주행마다 한 번)

```json
{ "start": { "lat": 37.4457, "lng": 126.6574 }, "end": { "lat": 37.4435, "lng": 126.6500 } }
```

응답: `{ "routeId": 1, "path": [ { "lat": 37.4457, "lng": 126.6574 }, ... ] }`

### `POST /api/location` — 현재 위치 (1초마다)

```json
{ "routeId": 1, "lat": 37.4476, "lng": 126.6526, "speed": 10.2, "timestampMs": 1795000037000 }
```

`timestampMs`(GPS 시각, UTC ms)는 재생 모드에서 Lane AI 재생 시각을 맞추는 데 씁니다. `currentLane`을 보내면 AI 대신 그 값을 씁니다.

```json
{
  "guide": true, "maneuver": "UTURN", "distanceToBranch": 247,
  "totalLanes": 5, "currentLane": 2, "currentLaneSource": "AI",
  "targetLanes": [1], "laneDirections": [["UTURN"], ["STRAIGHT"], ["STRAIGHT"], ["STRAIGHT"], ["STRAIGHT", "RIGHT"]],
  "message": "247m 앞 유턴. 현재 2차로, 왼쪽으로 1차로 이동하세요.", "arrived": false
}
```

차로 번호는 진행 방향 왼쪽부터 1번(좌회전·유턴 포켓 포함)입니다. 현재 차로를 모르면 `currentLane`은 `null`입니다.

### `GET /api/replay` — 재생할 주행 기록

`{ "sessionId": "...", "recordingStartMs": ..., "points": [ { "timestampMs": ..., "lat": ..., "lng": ..., "speed": ... } ] }`
(재생 기록이 없으면 `sessionId`가 `null`)

<br/>

## 팀

| 이름 | 담당 |
|---|---|
| 손승우 | 백엔드 · DB |
| 이채우 | Android 앱 |
| 김태현 | 차로 인식 AI |
