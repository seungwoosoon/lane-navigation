# 공개 샘플 실제 추론 확인 — 2026-10-08

공식 UFLD v2 CULane ResNet18 가중치와 공식 저장소의 `example.mp4`를 사용했습니다.
학습·정답 데이터 없이 실행 경로와 파일 출력을 점검한 결과입니다. 정확도 평가가 아닙니다.

## 실행 조건

```powershell
.\.venv\Scripts\python.exe scripts/run_detection.py --video data/sample/ufld_example.mp4 --weights models/weights/culane_res18.pth --output outputs/ufld_sample --camera-id sample --max-frames 60
```

- Windows, Python 3.14.4, CPU 4 threads.
- torch 2.9.1+cpu, torchvision 0.24.1+cpu, OpenCV 4.13.0, NumPy 2.5.2.
- 기준 공식 커밋: `c903880678454dfd9b55a63022368db05c00bc6d`.
- 영상: 1280×720, 명목 25 FPS, 보고된 전체 길이 1260프레임 중 처음 60프레임만 처리.
- 검출 결과가 있는 프레임: 60/60. 누적 유효 경계 슬롯: 206.
- 전처리·추론·좌표 복원 누적: 약 28.78초, 약 2.09 FPS. 파일 출력 포함 처리 약 29.84초.
  모델 로딩과 해시 계산 시간은 처리 속도에 포함하지 않습니다. 실시간 동작을 충족한다는 뜻이 아닙니다.
- 촬영 UTC 없음: `timestamp_ms=null`, 영상 내 상대 시간과 출처를 기록.
- 첫 프레임 오버레이를 시각적으로 점검했습니다. 차로 번호·차선 종류를 정답과 비교하지 않았습니다.

## 재현 식별자

```text
weights SHA256: 956616371ee758551c455d28c1c8a9732b39d7fbd85bc70b9869424a5121f967
video SHA256:   651949922042c223375d40bf8929def97dbb62c6ade651d1f5b08eea47127d5f
```

두 해시는 내려받은 파일에서 계산한 식별자이며 원저자의 서명/검증값을 의미하지 않습니다.
원본과 출력 파일은 Git 제외 경로에만 저장합니다. 결과는 `outputs/ufld_sample/`의
`boundaries.jsonl`, `overlay.mp4`, `preview.jpg`, `summary.json`입니다.

남은 실제 검증: 좌·우 비스듬한 카메라의 검출 품질, 가림/야간/곡선, 시계 정렬, 렌즈 왜곡,
한국 도로 영상, HD Map 후보 차로 정합. 이 결과로 절대 차로 추정이나 RF 학습 완료를 주장하지 않습니다.
