# 서버와 Lane AI 유턴 재생 연동 명세 v0.2

서버가 경로의 다음 분기점과 분석 필요 여부를 판단합니다. 분석이 필요한 동안
Lane AI에 현재 재생 시각을 보내고, 받은 차로 추정값과 이동 안내를 앱에 전달합니다.
앱은 결과를 화면에 표시합니다. 서버는 분기점 통과나 경로 변경 이후 요청을 중단합니다.

## 각자 개발하는 환경에서 연결

서버 팀원은 자기 노트북에서 **Spring Boot 개발 서버와 Lane AI 재생 API를 둘 다 실행**합니다.
Spring Boot에서 AI를 호출하는 주소는 `http://127.0.0.1:8765`입니다.
앱은 팀에서 쓰는 Spring Boot 개발 서버 주소로 연결합니다.

AI 담당자는 아래 세 파일을 서버 팀원에게 전달합니다. 영상, 정밀지도 또는 추론 라이브러리는
첫 연동에 필요하지 않습니다. 결과 JSON과 녹화 정보 JSON은 Git에서 제외되므로 별도로 전달합니다.

| 파일 | 용도 |
|---|---|
| `serve_lane_replay.py` | Python으로 띄우는 HTTP 재생 API |
| `unified_uturn_final.json` | 유턴 접근 구간의 시각별 차로 추정·안내 결과 |
| `recording.json` | 녹화 세션 ID와 시작 시각 |

세 파일을 같은 폴더에 두고 그 폴더에서 다음을 실행합니다. 결과 파일 생성 방법은
[실행 안내](../README.md)에 있습니다.

```powershell
py .\serve_lane_replay.py --result .\unified_uturn_final.json --recording .\recording.json
```

다른 PowerShell에서 연결 확인과 첫 안내 요청을 실행합니다.

```powershell
Invoke-RestMethod http://127.0.0.1:8765/health

$body = @{
    sessionId = 'drive_20261124_172325'
    maneuverLinkId = 'A223AA160179'
    recordingElapsedMs = 300100
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8765/v1/replay/lane-estimate' -ContentType 'application/json' -Body $body
```

## 서버 요청과 앱 표시

| 요청 필드 | 유턴 재생 테스트에서 사용하는 값 |
|---|---|
| `sessionId` | `drive_20261124_172325` |
| `maneuverLinkId` | AI 담당자가 확인한 A2 유턴 연결 링크 `A223AA160179` |
| `recordingElapsedMs` | 녹화 시작부터 흐른 밀리초. 첫 테스트에서는 아래 시각을 서버에서 지정합니다. |

| 테스트 재생 시각 | 예상 응답 | 앱 표시 |
|---|---|---|
| 300100ms | `status=VALID`, `currentLane=2`, `targetLane=1`, `movement=LEFT`, `laneChanges=1` | 왼쪽으로 한 차로 이동 준비 |
| 306100ms | `status=VALID`, `currentLane=1`, `targetLane=1`, `movement=KEEP`, `laneChanges=0` | 현재 차로 유지 |
| 280000ms | `status=UNKNOWN`, `movement=null` | 차로 이동 표시 없이 기본 안내 |

이동 값은 `LEFT`, `RIGHT`, `KEEP`, `null`입니다. `status=VALID`인데도
`movement=null`일 수 있습니다. 예를 들어 아직 목표 차로가 열리지 않은 시점에는
현재 차로만 반환합니다. `analyzedElapsedMs`와 `timestampMs`는 실제 선택된 분석 결과 시각입니다.
요청 시각보다 나중의 분석 결과는 반환하지 않습니다.

올바른 요청에 대응하는 결과가 없거나 낡았으면 HTTP 200의 `UNKNOWN`을 반환합니다.
요청 형식이 잘못됐으면 400, 세션 또는 회전 링크가 다르면 409입니다.
유턴 결과 파일의 분석 범위는 녹화 기준 약 288~308초입니다. 서버의 접근 조건이 더 일찍
충족되면 그 시점부터 요청할 수 있고, 분석 결과가 나오기 전에는 기본 안내를 유지합니다.

## 검증 순서

1. **고정 시각으로 연동**을 확인합니다. 서버가 `300100`과 `306100`을 순서대로 보내고
   앱이 이동 준비에서 차로 유지로 바뀌면 통과입니다. 이 단계의 회전 링크 ID와 시각은
   테스트 값이므로 서버의 지도 DB 조회나 GPS 재생 없이 확인할 수 있습니다.
2. **GPS 재생으로 접근 조건**을 확인합니다. 동일 세션의 `gps.csv`를 추가로 받아 서버에서
   시간순으로 재생합니다. GPS 위치가 접근 조건을 충족하면
   `recordingElapsedMs = gps.timestamp_ms - recordingStartTimestampMs`로 요청 시각을 계산합니다.
   녹화 시작 시각은 `recording.json`의 카메라 시작 시각 중 가장 이른 UTC 밀리초입니다.
   교차로 통과 또는 경로가 바뀐 후에는 기존 요청을 중단하는지 확인합니다.

현재 API의 `mode`는 `REPLAY`입니다. 실시간 단계의 카메라 입력 전달 방식과, 일반 경로에서
회전 지점을 A2 링크로 찾는 방법은 팀에서 후속으로 정합니다.
