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
2. `network/ApiClient.kt` 의 `BASE_URL` 을 서버 주소로 변경합니다
3. 카카오맵 API 키를 설정하고 빌드합니다

### 데이터베이스

PostgreSQL에 PostGIS 확장을 설치한 뒤, 국토지리정보원 정밀도로지도(EPSG:5179)를 적재합니다.

```sql
CREATE EXTENSION postgis;
```

<br/>

## API

### 경로 조회

```http
POST /api/route
```

**Request**

```json
{
  "startLat": 37.4501,
  "startLng": 126.6531,
  "endLat": 37.4612,
  "endLng": 126.6702
}
```

**Response**

```json
{
  "path": [
    { "lat": 37.4501, "lng": 126.6531 },
    { "lat": 37.4523, "lng": 126.6558 }
  ]
}
```

<br/>

## 팀

| 이름 | 담당 |
|---|---|
| 손승우 | 백엔드 · DB |
| 이채우 | Android 앱 |
| 김태현 | 차로 인식 AI |
