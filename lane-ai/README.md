# Lane AI

좌·우 카메라 관측과 GPS/HD Map 후보 차로를 이용한 현재 차로 추정 연구 모듈입니다.

## 현재 구현 범위

현재는 **오프라인 데이터 준비 + UFLD v2 검출 단계**입니다. 세션 메타데이터·GPS·프레임 시각과
HD Map 파일 구성을 검사하고, 실제 사전학습 모델로 영상에서 차선 픽셀 좌표·검출 품질과
오버레이를 저장합니다. 카메라 보정, BEV, 기하 점수 계산, 현재 차로 추정, RF 학습은 후속 단계입니다.

입력 검증 도구는 Python 3.10 이상 표준 라이브러리만으로 실행합니다. 실제 추론은
`requirements-inference.txt`의 별도 의존성이 필요합니다. Windows Python 3.14 CPU 환경으로 검증합니다.

## Windows에서 카메라 두 대 연결 확인

카메라를 연결한 노트북에서 `lane-ai` 폴더를 기준으로 실행합니다. 이 도구는 화면만 보여주며
영상 저장·프레임 동기화·AI 추론은 하지 않습니다. OpenCV 설치가 필요합니다.

```powershell
.\.venv\Scripts\python.exe scripts/test_dual_camera.py --camera-ids 1 2
```

`1 2`는 예시 번호입니다. 내장 카메라가 표시되거나 열기에 실패하면 `0 1`, `0 2`, `2 3`처럼
번호를 바꿔 두 USB 카메라를 찾습니다. 렌즈를 하나씩 가려 LEFT/RIGHT가 맞는지 확인하고,
미리보기 창에서 `q`를 눌러 종료합니다. 기본 요청값은 1280×720, 30fps이며
`--width`, `--height`, `--fps`로 변경할 수 있습니다. 표시된 드라이버 FPS는 실제 수신 FPS나
두 카메라의 동기화 증거가 아닙니다. `--duration 30`을 추가하면 30초 뒤 자동 종료하며,
실제로 프로그램이 읽은 프레임 수와 평균 읽기 FPS를 출력합니다. 이는 센서 노출 시각 기준 FPS가
아니고 동기화·중복 프레임을 검증하지 않습니다.
한 대씩 시험하려면 `--camera-ids 1` 또는 `--camera-ids 2`를 사용합니다. FOURCC는 드라이버가
보고한 영상 포맷이며 설정 요청 성공만으로 실제 장치 설정이 확정되지는 않습니다. 해상도와 FPS를
설정한 뒤 MJPG를 요청하고, 최종 설정을 다시 읽어 확인합니다.

### 좌·우 영상 녹화

미리보기에서 LEFT/RIGHT 장치 번호를 확인한 뒤 저장소 루트에서 실행합니다. 출력 폴더는
새 이름을 사용해야 합니다. 아래 명령은 시간 제한 없이 녹화하며, `Ctrl+C`로 종료하면 MP4 파일을 마무리합니다. 각 카메라의 MP4와
프레임 수신 시각 CSV, 실행 요약을 `lane-ai/data/drives/`에 저장합니다.

```powershell
.\lane-ai\.venv\Scripts\python.exe .\lane-ai\scripts\record_dual_camera.py --camera-ids 1 2 --width 1280 --height 720 --fps 30 --output .\lane-ai\data\drives\drive_001
```

녹화 중 좌우 화면을 확인하려면 `--preview`를 추가합니다. 미리보기 창에서 `q`를 눌러도 두 MP4를
마무리합니다. 미리보기 화면은 축소하고 갱신 빈도를 제한합니다. 녹화 파일에는 원본 해상도로 저장합니다.

`capture_summary.json`의 `application_record_fps`는 프로그램이 저장한 프레임의 평균 속도입니다.
MP4 헤더의 30fps는 요청한 **명목 재생 속도**일 뿐 실제 촬영 속도가 아닙니다. CSV의 시각은
노트북이 프레임을 받은 시각이며 카메라 센서 노출 시각이 아닙니다. 따라서 이 시험으로 좌우
카메라가 정확히 동기화되었다고 주장하지 않습니다. GPS가 없는 이 첫 시험은 `recording.json`
주행 세션 계약을 아직 충족하지 않으며, 두 카메라의 실제 주행 분석용 데이터로 사용하려면
시계 정렬 방법과 GPS를 추가로 확인해야 합니다.

## 차선 검출 실행

`lane-ai` 폴더 기준입니다. 설치·공식 가중치와 샘플 다운로드는 처음 한 번 실행합니다.

```powershell
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-inference.txt
.\.venv\Scripts\python.exe scripts/download_ufld_assets.py --weights --sample
.\.venv\Scripts\python.exe scripts/run_detection.py --video data/sample/ufld_example.mp4 --weights models/weights/culane_res18.pth --output outputs/ufld_sample --camera-id sample --max-frames 60
```

이미 다운로드한 파일과 기존 출력 폴더는 덮어쓰지 않습니다. 다시 실행할 때는 새 출력 경로를 사용합니다.
팀 영상이 들어오면 `--video`, `--camera-id`를 변경하고 촬영 시각 CSV가 있으면
`--frame-timestamps`를 지정합니다. GPS 없이도 검출 자체는 실행됩니다. 모든 프레임을 처리하려면
`--max-frames`를 생략합니다. 자세한 시각 처리·모델 제약·출처는 [UFLD 실행 안내](docs/ufld-inference.md),
실제로 실행한 공개 영상 결과는 [검증 기록](docs/ufld-smoke-test.md)을 봅니다.

## 데이터가 도착하면

녹화 시각 CSV와 `capture_summary.json`이 있는 세션에 NMEA 로그를 연결하려면 `lane-ai`에서 실행합니다.

```powershell
.\.venv\Scripts\python.exe scripts/prepare_gps.py --nmea C:\path\to\nmea.log --session data/drives/drive_20261124_172325
```

원본 로그를 `gps_source.nmea`로 복사하고, `gps.csv`, `recording.json`, 좌우 `*_gps_frames.csv`,
GPS 위치점 `gps_track.geojson`, 진단 기록 `gps_preparation.json`을 만듭니다. 이미 준비된 파일은
덮어쓰지 않습니다. 영상·기존 프레임 시각 CSV는 보존합니다. GPS를 늦게 켠 시간만큼 시각을
앞당기지 않으며 UTC 기록 그대로 대응합니다. 첫 GPS 이전/마지막 이후 및 오래된 위치는 빈값으로
남깁니다. 기본 `--max-age-ms 2000`은 초기 준비 정책이며, 검증된 동기화 허용 오차가 아닙니다.
음수 속도와 범위 밖 방향값은 빈값으로 기록하고 원본 값은 NMEA에 보관합니다. 시간 대응만으로
위치 정확도·시계 오차·센서 노출 동기화가 검증되지는 않습니다.

저장소 루트에서 `lane-ai`로 이동한 후 실행합니다. 아래는 Windows 명령이며, 다른 환경에서는
`py` 대신 해당 Python 실행 명령을 사용합니다.

```powershell
cd lane-ai
py scripts/validate_drive.py data/drives/run_001
py scripts/inspect_hdmap.py data/hdmap
py -m unittest discover -s tests -v
```

1. 원본 영상·GPS를 `data/drives/run_001/`에 둡니다.
2. `configs/recording.example.json`을 해당 폴더의 `recording.json`으로 복사하고 측정값을 입력합니다.
   예시의 `null`은 미설정 값이며 그대로는 검증을 통과하지 않습니다.
3. HD Map 원본을 `data/hdmap/`에 둡니다. ZIP은 동반 파일과 디렉터리 구조를 유지해 해제합니다.
4. 위 두 명령으로 오류·경고를 확인합니다. JSON의 `ok: true`는 **형식 검사 통과**이며,
   실제 디코딩·동기화 정확도·좌표계·지도 범위·추론 가능성을 보증하지 않습니다.

`validate_drive.py`는 차로 추정 실험용 영상+GPS 묶음을 검사합니다. `run_detection.py`는
GPS 없이 영상만으로 차선을 검출합니다. 두 명령의 검사·처리 범위가 다릅니다.

## 실제 주행 구간 오프라인 분석

`analyze_approach.py`는 좌우 원본 영상, 프레임 시각 CSV, `gps.csv`와 A2·B2 지도를 읽어
UFLD v2 검출부터 현재 차로와 이동 방향 계산까지 실행합니다. 아래 예시는 `lane-ai`에서
11월 24일 주행 자료로 실행합니다.

```powershell
$inputs = @(
    'data/drives/drive_20261124_172325',
    '--hdmap', 'data/hdmap/B110_SEC001_2023',
    '--calibration', 'configs/bev_20261124_trial.json',
    '--weights', 'models/weights/culane_res18.pth'
)

# 실제 유턴 접근. 먼저 GPS로 초기화하고 영상에서 차로 변경을 확인합니다.
.\.venv\Scripts\python.exe scripts/analyze_approach.py @inputs --start 288 --end 308 --gps-from 271.7 --route-link A223AA160179 --output outputs/uturn_approach.json

# 두 번째 교차로. 오른쪽 연석으로 맨 오른쪽 차로를 잡고 시험용 좌회전 차로를 사용합니다.
.\.venv\Scripts\python.exe scripts/analyze_approach.py @inputs --camera-from 414 --start 415.8 --end 426.2 --anchor road-edge --numbering map --target-link A223AA160920 --output outputs/junction_approach.json
```

`--start`와 `--end`는 녹화 시작 기준 초입니다. `--end` 시각은 범위에서 제외합니다.
지도 폴더에는 EPSG:5179인 `a2_link`와 `b2_surfacelinemark` 파일 묶음이 있어야 합니다.
같은 출력 파일에 다시 실행할 때는 새 파일명을 사용합니다.

각 결과는 UTC 밀리초 `timestamp_ms`, `current_lane`, `map_link_id`, `status`,
`evidence`를 포함합니다. 목표 A2 링크를 넘기면 `target_lane`, `movement`,
`lane_changes`도 제공합니다. 목표가 없거나 해당 시각에 연결되지 않으면 이동 안내 값은
비어 있습니다. `physical` 번호는 화면에서 왼쪽부터 센 순서이고, `map` 번호는 지도 속성입니다.

이 명령은 녹화 검증용입니다. 오른쪽 카메라의 BEV 보정값, GPS 초기화와 연석 검출은
현재 주행 자료에서 검증한 조건을 사용합니다. 이후 연동에서는 앱이나 서버가 분석할 시점과
목표 A2 링크를 제공하고, 앱이 반환값으로 이동 안내를 표시할 수 있습니다.
서버가 저장된 유턴 결과를 HTTP로 받아 앱까지 연결하는 방법은
[유턴 재생 연동 안내](docs/server-replay-integration.md)에 있습니다.

## 구조

```text
lane-ai/
├── configs/                # 측정값을 채우기 전의 설정 템플릿
├── docs/
│   ├── data-contract.md    # 팀원에게 받을 파일·시간·단위 규칙
│   ├── module-contracts.md # AI 내부 역할과 의존 관계 초안
│   └── development-plan.md # 이슈/PR 분할과 완료 조건
├── data/
│   ├── drives/             # 세션별 원본 영상·GPS, Git 제외
│   ├── sample/             # 공개 검증 영상, Git 제외
│   ├── hdmap/              # 원본 지도, Git 제외
│   ├── calibration/        # AI 담당이 측정한 보정값, Git 제외
│   └── labels/             # 정답 라벨, 추론 입력과 분리, Git 제외
├── models/weights/         # 실제 가중치, Git 제외
├── src/
│   ├── contracts.py        # 내부 픽셀 관측·점수·결과 계약 초안
│   ├── data_validation.py  # 입력 파일 구성과 시간/값 검사
│   ├── video_reader.py     # 영상 프레임·시각 읽기
│   ├── ufld_model.py       # 공식 모델의 추론 전용 적응본
│   ├── detection.py        # 실제 검출과 원본 픽셀 좌표 복원
│   └── detection_runner.py # JSONL·오버레이·실행 기록 저장
├── scripts/
│   ├── validate_drive.py   # 영상/GPS 세션 검사 CLI
│   ├── inspect_hdmap.py    # 지도 파일 구성 검사 CLI
│   ├── download_ufld_assets.py # 공식 가중치/샘플의 명시적 다운로드
│   └── run_detection.py    # 단일 영상 검출 CLI
├── third_party/            # 원본 코드 라이선스
├── tests/                  # 임시 디렉터리에서 만든 합성 입력으로 검증
└── outputs/                # 결과 영상·좌표·평가, Git 제외
```

원본 파일은 Git에서 제외하고 `.gitkeep`만 추적합니다. 테스트가 만드는 영상·지도 파일은
파일 검사만을 위한 **합성 바이트** 또는 생성한 단색 영상이며 실제 주행 데이터가 아닙니다.
전체 테스트는 `.venv\Scripts\python.exe -m unittest discover -s tests -v`로 실행합니다.
의존성 없는 기본 Python에서는 추론 관련 테스트가 건너뛰어집니다.

자세한 수신 형식은 [데이터 계약](docs/data-contract.md), 향후 함수의 책임은
[모듈 계약](docs/module-contracts.md), 진행 순서는 [개발 계획](docs/development-plan.md)을 봅니다.
APP/SERVER 통신, 활성화 트리거, 실행 위치와 서버의 역할 분담은 아직 확정하지 않습니다.
