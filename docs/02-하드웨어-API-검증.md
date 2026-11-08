# 2단계 사전 조사 — Galaxy Z Flip 6 하드웨어 / API 실제 지원 범위

> 원칙: **"지원된다"는 근거 없이 가정하지 않는다.** 아래 표는 공개 스펙·공식 문서로 확인한 사실과,
> 앱이 런타임에 Camera2로 직접 판정하는 항목을 구분해 기록한다.

## 1. 카메라 하드웨어 (공개 스펙)

| 구분 | 사양 |
|---|---|
| 후면 광각 | 50MP, f/1.8, 23mm(35mm 환산), 1.0µm, 듀얼픽셀 PDAF, **OIS** |
| 후면 초광각 | 12MP, f/2.2, 123˚(약 13~16mm 환산) |
| 전면 | 10MP, f/2.2, 23mm 환산 |
| 동영상 | 4K@30/60, 1080p@60/120/240, 720p@960(슈퍼슬로우모), HDR10+ |
| SoC | Snapdragon 8 Gen 3 for Galaxy (Hexagon NPU, Adreno 750) |
| 메인 디스플레이 | 6.7" 1080×2640, 1~120Hz |
| 커버 디스플레이 | 3.4" 720×748, 60Hz |

출처: 삼성 공개 스펙 및 주요 매체 스펙시트(2024-07 공개).

### 우리 앱과의 관계
* **광각/초광각/전면 3개 카메라**라는 요구사항은 하드웨어와 일치한다.
* 하지만 "물리 렌즈 3개"가 곧 "Camera2 카메라 ID 3개"를 의미하지 **않는다**.
  삼성은 논리 카메라(ultra-wide + wide를 묶은 logical multi-camera)를 노출하는 경우가 있으며,
  물리 카메라 ID는 `CameraCharacteristics.getPhysicalCameraIds()`로만 알 수 있다.
  → 앱은 **`cameraIdList` + `physicalCameraIds`를 모두 열거**하고, 초점거리/센서 크기로
  광각·초광각을 분류한다(`LensClassifier`). 렌즈 개수를 하드코딩하지 않는다.

## 2. RAW / 수동 촬영 (Camera2)

| 항목 | 공개 정보 | 앱에서의 처리 |
|---|---|---|
| Expert RAW | **Z Flip 6는 Flip 시리즈 최초로 Expert RAW 지원**(삼성 자사 앱) | 삼성 앱의 기능이며 서드파티 API가 아니다. 우리 앱은 자체 RAW 경로만 사용 |
| 서드파티 RAW | 기기/펌웨어에 따라 다름. 삼성은 일부 렌즈(초광각·전면)에서 서드파티 RAW를 제한하는 사례가 다수 보고됨 | `REQUEST_AVAILABLE_CAPABILITIES_RAW` + `getOutputSizes(RAW_SENSOR)` **둘 다** 있을 때만 RAW UI 노출, 없으면 숨김 |
| 수동 노출 | `MANUAL_SENSOR` capability 필요 | `SENSOR_INFO_EXPOSURE_TIME_RANGE`, `SENSOR_INFO_SENSITIVITY_RANGE` 실측값으로 슬라이더 범위 결정 |
| 수동 초점 | `CONTROL_AF_AVAILABLE_MODES`에 `OFF` 포함 + `LENS_INFO_MINIMUM_FOCUS_DISTANCE > 0` | 둘 다 만족할 때만 MF 슬라이더 노출 |
| 수동 WB | `CONTROL_AWB_AVAILABLE_MODES`에 `OFF` 포함 | `COLOR_CORRECTION_GAINS`로 Kelvin→RGGB 게인 적용 |
| HEIF / Ultra HDR(JPEG_R) | API 33/34 이후 포맷. 기기 지원 여부는 StreamConfigurationMap에만 나타남 | 런타임 조회만 하고, **1단계에서는 JPEG 저장만 구현**(다른 포맷은 UI에서 선택 불가) |

> 결론: RAW·수동 기능의 "실제 지원 여부"는 **기기에서 앱의 capability 리포트를 뽑아 확인해야 한다.**
> 앱 내 `지원 정보` 시트가 그 리포트이며, `capabilities_<모델>.json`으로 저장된다.

## 3. 커버 디스플레이 (Z Flip 6)

| 확인 사항 | 사실 |
|---|---|
| 서드파티 커버 화면 API | **공식 API 없음** |
| 실행 경로 | 사용자가 *설정 → Advanced features → Labs → Apps allowed on cover screen* 활성화 후 **Good Lock → MultiStar → I♡Galaxy Foldable → Launcher widget**에 앱을 추가해야 커버 화면에서 실행 가능 |
| 함의 | 앱이 "커버 화면에 그린다"를 스스로 구현/표시할 수 없음. **사용자 설정에 의존**한다 |
| 우리 앱 설계 | 커버 화면(720×748)에서도 레이아웃이 깨지지 않도록 **반응형 UI**를 2단계에서 구현. "커버 화면 감지 = 창 크기/종횡비 기반"으로 처리하며 기능이 켜진 것처럼 표시하지 않음 |

## 4. Flex Mode / 힌지

| 확인 사항 | 사실 |
|---|---|
| Jetpack WindowManager | `FoldingFeature`의 `state == HALF_OPENED`, `orientation`, `occlusionType`을 **서드파티 앱이 사용 가능** |
| 연속 힌지 각도 | `Sensor.TYPE_HINGE_ANGLE`(API 30+, 기기 지원 시). 0/90/180 등 계단식 보고 사례가 있어 **정밀 각도는 보조 정보로만 사용** |
| 삼성 Flex Mode 패널 | 삼성 자사 카메라 전용 UX. 서드파티는 WindowManager의 half-opened 상태로 **자체 구현** |
| 함의 | 2단계에서 `FoldingFeature` 기반 분할 레이아웃 구현(프리뷰 상단 / 컨트롤 하단). "삼성 Flex Mode 패널"을 그대로 쓰는 것이 아님을 UI에 명시 |

## 5. NPU / GPU 가속 (LiteRT)

| 항목 | 사실 |
|---|---|
| GPU delegate | LiteRT GPU delegate는 **OpenCL 기반**으로 Adreno 750에서 사용 가능(16bit/32bit float) |
| NPU delegate | Qualcomm **QNN delegate**(AI Engine Direct)는 별도 네이티브 라이브러리(`libQnnTFLiteDelegate.so`, `libQnnHtp.so` 등)가 필요하며, 배포·서명·기기 활성화 조건이 있다 |
| 결론 | **"Flip 6 = NPU 사용 가능"이라고 가정하지 않는다.** 3단계 AI Core에서 ①런타임에 QNN 라이브러리 로드 성공 여부를 확인하고 ②실패하면 GPU delegate ③그것도 실패하면 XNNPACK(CPU)로 자동 전환하는 구조로 구현한다 |
| 지금(1단계) | JNI 네이티브 라이브러리 로딩 경로만 검증 완료. 추론 엔진은 3단계 |

## 6. 센서 (6단계 슈퍼스테디 대비)

* `SensorManager`로 `TYPE_GYROSCOPE`, `TYPE_ACCELEROMETER`, `TYPE_ROTATION_VECTOR` 사용 가능.
* Camera2 `CaptureResult.SENSOR_TIMESTAMP`와 `SensorEvent.timestamp`(둘 다 나노초, `elapsedRealtimeNanos` 기준)로
  프레임-센서 시간 정합 가능 → 6단계에서 사용.
* 실제 샘플링 레이트/노이즈는 기기에서만 확인 가능 → 미검증 항목으로 관리.
