# UFLD v2 검출 실행 (개발 단계 2)

## 구현 범위

공식 CULane ResNet18 가중치로 실제 영상 프레임을 추론하고 원본 영상 기준 차선 경계 픽셀 좌표를
JSONL로 저장합니다. 오버레이 MP4, 첫 프레임 미리보기, 실행 메타데이터도 함께 저장합니다.
GPS·HD Map·카메라 보정 없이 검출만 실행할 수 있습니다. 절대 차로 번호는 출력하지 않습니다.

모델 설정은 `configs/ufld_culane_res18.json`에 기록한 **고정 프로파일**입니다. 이 JSON은
재현을 위한 명세이며 런타임 설정 파일이 아닙니다. 값을 편집해 다른 가중치를 호환시키지 않습니다.
다른 데이터셋/백본 지원은 전처리·anchor·checkpoint를 함께 검증한 뒤 추가합니다.

## 설치와 공식 파일 준비

Windows PowerShell, `lane-ai` 폴더 기준:

```powershell
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-inference.txt
.\.venv\Scripts\python.exe scripts/download_ufld_assets.py --weights --sample
```

검증 환경은 Windows / Python 3.14 / CPU용 PyTorch입니다. 다른 환경의 실행 가능성은 별도
확인해야 합니다. 현재 requirements는 CPU용이며 GPU 드라이버를 변경하지 않습니다.
가중치는 약 825MB, 공식 예제 영상은 약 25MB입니다. 명시적인 다운로드 명령에서만 내려받고
추론 시에는 네트워크를 사용하지 않습니다. 가중치·원본 영상·가상환경·결과는 Git에서 제외합니다.

Google Drive가 다운로드를 거부하면 아래 공식 README의 CULane ResNet18 링크에서 파일을 받아
`models/weights/culane_res18.pth`에 둡니다. 다른 모델을 같은 이름으로 바꾸면 안 됩니다.
다운로더는 기존 파일과 `.part` 파일을 덮어쓰지 않습니다. 실패한 `.part`는 확인 후 별도 보관하거나
직접 정리하고 재시도합니다. 다운로드 후 생성한 `.source.json`의 SHA256은 로컬 계산값이며
제작자가 서명하거나 제공한 체크섬이라는 뜻은 아닙니다.

## 공개 예제로 실행

```powershell
.\.venv\Scripts\python.exe scripts/run_detection.py --video data/sample/ufld_example.mp4 --weights models/weights/culane_res18.pth --output outputs/ufld_sample --camera-id sample --max-frames 60
```

`--max-frames`를 생략하면 끝까지 처리합니다. 출력 폴더가 이미 있으면 덮어쓰지 않고 실패합니다.
실행 중 실패하면 일부 결과가 남을 수 있으며 **`summary.json`의 status가 complete일 때만 완료**입니다.
오버레이는 원본 명목 FPS의 무음 영상입니다. 가변 FPS 원본의 정확한 시간 분석은 JSONL을 사용합니다.

## 팀 영상으로 실행

같은 명령의 `--video`와 `--camera-id`만 좌·우 영상에 맞춰 지정하면 각각 검출할 수 있습니다.
이 단계는 두 카메라를 동기화/융합하지 않습니다.

```powershell
.\.venv\Scripts\python.exe scripts/run_detection.py --video data/drives/run_001/left.mp4 --weights models/weights/culane_res18.pth --output outputs/run_001_left --camera-id left --frame-timestamps data/drives/run_001/left_frames.csv
```

시각 CSV는 `frame_id,timestamp_ms`이며 처리하는 모든 프레임의 시각이 있어야 합니다.
시각 CSV가 없으면 해당 옵션을 생략합니다. 시작 시각만 안다면 `--start-timestamp-ms`를 사용할 수
있지만 영상 내 상대 시각과 합친 **미검증 시계 정렬값**으로 명시합니다. 두 옵션은 동시에 사용할 수
없습니다. 아무 옵션도 없으면 UTC `timestamp_ms`는 null입니다.

## 출력 의미

- `boundaries.jsonl`: 프레임마다 camera_id/frame_id/시각/원본 크기/경계별 points_xy·valid·quality.
- `overlay.mp4`, `preview.jpg`: 원본 위에 픽셀 좌표를 표시. 슬롯 이름은 차량 차로 번호가 아닙니다.
- `summary.json`: 프레임 수·시간·라이브러리 버전·프로파일·입력/가중치 SHA256·제약사항.

`slot_0..3`은 모델의 프레임별 출력 슬롯이며 영속적인 추적 ID가 아닙니다. row 슬롯 1/2,
column 슬롯 0/3을 사용하는 공식 디코딩 규칙을 따릅니다. 원본 영상 크기로 환산하고 경계 끝점은
유효 픽셀 범위로 제한합니다. 이 프로파일의 최대 4개 경계 슬롯이 도로의 총 차로 수를 뜻하지 않습니다.

`quality`는 채택된 anchor에서 존재 분류 softmax의 평균입니다. 보정된 확률이나 현재 차로
신뢰도가 아닙니다. 실선/점선·중앙선·도로 가장자리 종류도 이 모델 출력만으로 분류하지 않습니다.
검출하지 못한 슬롯은 `valid: false`, 빈 좌표 목록, `quality: null`로 남깁니다.

## 테스트와 남은 검증

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

단위 테스트는 합성 logits·단색 영상으로 좌표/시간/파일 처리를 검증하며 정확도를 측정하지 않습니다.
모델은 실제 가중치가 없거나 state_dict가 다르면 실패하며 무작위 가중치로 추론하지 않습니다.
공개 예제 검증은 실행 여부 확인입니다. 한국 도로·좌우 비스듬한 카메라 시야의 검출 품질과 속도,
보정/지도 비교에 필요한 관측 정확도는 팀의 실제 영상으로 검증해야 합니다.

## 출처와 라이선스

- [공식 UFLD v2](https://github.com/cfzd/Ultra-Fast-Lane-Detection-v2)
- 기준 커밋: `c903880678454dfd9b55a63022368db05c00bc6d`
- `src/ufld_model.py`는 공식 `model/model_culane.py`, `model/backbone.py`의 추론 전용 적응본입니다.
- `src/detection.py`는 공식 `demo.py`, `data/dataset.py`, `utils/common.py`의 전처리·anchor 규칙을 따릅니다.
- 공식 코드의 MIT 고지 전문은 `third_party/ufldv2-LICENSE`에 보존했습니다. 데이터셋과 가중치의
  이용·재배포 조건은 별도로 확인하며 저장소에는 가중치나 공개 원본 영상을 재배포하지 않습니다.
