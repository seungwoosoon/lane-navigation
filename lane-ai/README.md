# Lane AI

좌·우 카메라 관측과 GPS/HD Map 후보 차로를 이용한 현재 차로 추정 연구 모듈입니다.

## 현재 구현 범위

현재는 **오프라인 데이터 준비 + UFLD v2 검출 단계**입니다. 세션 메타데이터·GPS·프레임 시각과
HD Map 파일 구성을 검사하고, 실제 사전학습 모델로 영상에서 차선 픽셀 좌표·검출 품질과
오버레이를 저장합니다. 카메라 보정, BEV, 기하 점수 계산, 현재 차로 추정, RF 학습은 후속 단계입니다.

입력 검증 도구는 Python 3.10 이상 표준 라이브러리만으로 실행합니다. 실제 추론은
`requirements-inference.txt`의 별도 의존성이 필요합니다. Windows Python 3.14 CPU 환경으로 검증합니다.

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
