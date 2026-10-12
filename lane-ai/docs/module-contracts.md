# 내부 모듈 계약 초안

데이터 검증 도구, 내부 데이터 구조, UFLD v2 CULane ResNet18 검출과 영상 실행 도구를 구현했습니다.
아래 표에서 detection 외 알고리즘 모듈은 해당 단계에 추가합니다. 인터페이스는 Android 통신
계약이 아니며 지도 스키마·카메라 보정 형식은 확인 후 연결합니다.

| 예정 모듈 | 입력 | 출력 / 책임 |
|---|---|---|
| detection.py | 원본 프레임, camera_id, frame_id, 촬영 시각 | CameraObservation: 원본 영상 픽셀 기준 경계 좌표·유효성·선택적 검출 품질 |
| calibration.py | 카메라별 관측 + 측정된 intrinsic/distortion/extrinsic | 왜곡 보정 관측과 보정 메타데이터; 원본/보정 좌표 중복 보정 방지 |
| bev.py | 보정 관측 + 확인된 지면 가정·카메라 자세 | 차량 기준 지면 좌표, 유효 관측 범위; 차량 좌표축/단위는 명시 필요 |
| fusion.py | 시각이 맞는 좌우 차량 기준 관측 | 중복 관측을 관리한 통합 관측; 동기화 허용 오차는 측정 후 정의 |
| map_matching.py | 통합 관측, GPS와 정확도, 후보 차로·지도 기하, 확인된 좌표 변환 | CandidateScore 목록: 동일 후보 집합에서 높은 값이 더 좋은 기하 점수와 사용 근거 |
| lane_estimator.py | 후보별 점수 + 후보 ID/차로 번호 대응 + 결정 정책 | LaneEstimate: 차로 ID·표시 번호·신뢰도·VALID/UNKNOWN |
| temporal_filter.py | 이전 추정 이력, 현재 추정, 시간 차이 | 시간적으로 안정화한 추정; 과거 결과를 정답으로 취급하거나 무기한 유지하지 않음 |
| features.py | 동일 카메라/GPS/지도 관측 | 스키마·단위·버전이 기록된 학습/추론 공통 특징; 정답은 별도 |

흐름은 `검출 → 보정 → BEV → 융합 → 후보 점수 → 차로 결정 → 시간적 안정화`입니다.
features는 같은 관측으로부터 별도로 파생하며, 지금 기하 기반 추정이 RF 학습에 의존하지 않습니다.

## 이미 정의한 데이터 구조

- PixelBoundary: 원본 영상의 `(x, y)` 픽셀 목록, 유효 여부, 선택적 detector-specific 품질.
- CameraObservation: 카메라/프레임 ID, 시각, 원본 크기, PixelBoundary 목록.
  촬영 UTC를 모르면 timestamp_ms는 null입니다. video_time_ms와 timestamp_source로 상대 시각과
  측정/추정 시각의 출처를 구분합니다. 영상 시작 시각을 새로 지어내지 않습니다.
- CandidateScore: lane_id, score, 사용한 근거. score는 확률이 아니며 실제 점수식은 미구현.
- LaneEstimate: road_segment_id, lane_id, lane_number, confidence, status, 판단 시각.
  UNKNOWN은 차로 ID와 번호가 모두 null입니다. 신뢰도를 아직 정의하지 못했다면 null을 사용합니다.
  신뢰도 숫자를 제시할 때는 계산 방법도 기록합니다. VALID에는 도로·차로·번호·신뢰도가 필요합니다.

## 실제 확인 전에는 확정하지 않는 항목

CULane ResNet18의 텐서·전처리·디코딩 규칙은 공식 코드를 확인해 구현했습니다. quality는
존재 분류 점수의 평균이며 보정된 확률이 아닙니다. 다른 모델 출력, 실선/점선 추가 분류,
지도 레이어와 필드, map/vehicle 좌표축·변환,
차로 번호 기준, 임계값, 신뢰도 보정 방법, 보정 파일 형식은 미확정입니다.
GPS로 도로 후보를 조회하는 작업의 담당도 팀 협의 대상입니다. AI는 우선 후보 집합을 입력받아
비교하며, 필요하면 별도의 오프라인 데이터 준비 도구를 만듭니다.

추론 코드에는 네트워크 호출, APP/SERVER API, AI 활성화 트리거, 가짜 추론 결과를 넣지 않습니다.
공식 파일 다운로드는 사용자가 별도로 실행하는 준비 스크립트에만 있습니다.
tests의 합성 파일은 검증기 테스트에만 쓰며 src의 실제 알고리즘 대용으로 사용하지 않습니다.
