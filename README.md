# AI Pro Camera — Galaxy Z Flip 6 전용 로컬 AI 카메라

Camera2 기반 전문 카메라 + 완전 오프라인 로컬 AI 보정 앱.
이 저장소는 **1단계(Camera2 엔진) → 2단계(UI/Flip) → 3단계(AI 사진 엔진) → 4단계(멀티프레임·야간·HDR)
→ 5단계(동영상 실시간/후처리) → 6단계(실험적 수평 잠금·발열 최적화)** 를 실제 빌드·테스트까지
통과시킨 상태이다. 미구현 항목은 `docs/04-구현상태.md`에 그대로 적혀 있다.

## 빠른 시작

```bash
export JAVA_HOME=<JDK 17>            # AGP 8.7 요구 사항
export ANDROID_HOME=<Android SDK 35> # platform 35, build-tools 35.0.0, ndk 27.0.12077973, cmake 3.22.1
./gradlew :app:assembleDebug                       # APK (arm64-v8a)
./gradlew :core:camera:testDebugUnitTest :core:ai:testDebugUnitTest
g++ -std=c++17 -O2 -I core/native/src/main/cpp \
    core/native/src/main/cpp/aipro_core.cpp core/native/src/main/cpp/image_ops.cpp \
    core/native/src/testHost/host_test.cpp -o /tmp/native_test && /tmp/native_test
g++ -std=c++17 -O2 -I core/native/src/main/cpp \
    core/native/src/main/cpp/image_ops.cpp core/native/src/testHost/host_image_test.cpp -o /tmp/img_test && /tmp/img_test
g++ -std=c++17 -O2 -I core/native/src/main/cpp \
    core/native/src/main/cpp/multiframe.cpp core/native/src/testHost/host_multiframe_test.cpp -o /tmp/mf_test && /tmp/mf_test
g++ -std=c++17 -O2 -I core/native/src/main/cpp \
    core/native/src/main/cpp/video_ops.cpp core/native/src/testHost/host_video_test.cpp -o /tmp/vid_test && /tmp/vid_test
./gradlew :app:assembleRelease   # 설치 가능한 릴리스 APK
```

APK 위치
* 디버그: `app/build/outputs/apk/debug/app-debug.apk` (43.9 MB, arm64 전용)
* **릴리스: `app/build/outputs/apk/release/app-release.apk` (35.0 MB, v1.0.0, 로컬 디버그 키 서명 → 바로 설치 가능)**

## 무엇이 동작하나 (검증된 것)

| 영역 | 내용 |
|---|---|
| 카메라 엔진 | 카메라/렌즈 탐색, 프리뷰, JPEG 촬영(RAW 옵션), 동영상(HEVC/H.264), AF·AE 수렴 대기 |
| 수동 제어 | ISO·셔터·MF·WB(Kelvin)·EV·줌 — **기기가 보고한 범위/지원 여부로만 UI 생성** |
| 진단 | `지원 정보` 시트 + JSON 내보내기(카메라 실측 capability) |
| UI | **갤럭시 기본 스타일 / 프로 카메라 스타일 선택**, 촬영 용도(자동·인물·풍경·도시), 야간 토글, 프로 패널, 히스토그램 |
| Flip | Flex Mode(WindowManager FoldingFeature + 힌지 센서), 좁은 화면(커버 화면) 컴팩트 레이아웃 |
| 저장 | `Pictures/AIProCamera/{Original,Enhanced,Video/Original,Video/Enhanced}`, 원본/보정본 분리, JSON 메타데이터 |
| AI | LiteRT(2.3.2) 연결, NPU→GPU→CPU 자동 전환(실측), 장면 분석, 노이즈 제거, 업스케일, 인물 처리, **디스크 영속 작업 큐**(중단/재개/재시도), 발열·배터리 게이팅 |
| 모델 관리 | 사용자가 `.tflite`를 설치/삭제 (APK 미포함), FlatBuffer·인터프리터 검증 + SHA-256 |
| 멀티프레임(4단계) | 야간 스태킹(움직임 인식 가중·고스트 억제), HDR 노출 합성, 정렬 시프트/노이즈/움직임 지표를 요약·메타데이터로 기록 |
| 동영상(5단계) | 실시간 GL 톤 체인(프리뷰 전용 → 원본 녹화 절대 중단 없음), 녹화 후 2초 세그먼트 단위 **재개 가능** 후처리(WorkManager), 오디오 무재인코딩 복사 |
| 수평 잠금(6단계) | 자이로+가속도 상보 필터(센서 타임스탬프 기반) → 프리뷰/Enhanced 영상에 롤 보정. 원본 정지 이미지는 회전하지 않음 |
| 발열 최적화 | `PerformanceGovernor` 레벨에 따라 멀티프레임 장수 축소(6→4→3), 업스케일 상한, 후처리 보류, 상단에 발열 상태 상시 표시 |

## 정직하게 밝히는 제약

1. **실기기 미검증**: 카메라/RAW/커버 화면/NPU/발열은 이 개발 환경(기기 없음)에서 확인할 수 없다.
   → `docs/05-기기테스트-체크리스트.md`의 절차로 기기에서 확인해야 한다.
2. **AI 모델은 기본 미설치**: 배포물에 모델을 넣지 않는 것이 요구사항이다. 모델이 없으면
   네이티브(고전) 경로로 보정하며, UI·메타데이터에 그 경로가 그대로 기록된다.
3. **NPU**: Qualcomm QNN delegate 라이브러리는 AAR에 없다. 앱은 로드 성공 여부를 실제로 확인하고,
   실패하면 GPU → CPU로 내려간다. "NPU 된다"고 가정하지 않는다.
4. **커버 화면**: 삼성 공식 서드파티 API가 없다. 사용자가 *Labs → Apps allowed on cover screen* +
   *Good Lock/MultiStar Launcher widget* 을 설정해야 실행 가능하며, 앱은 레이아웃만 대응한다.
5. **미구현**: Focus Peaking, HEIF/Ultra HDR 저장, RAW 현상 파이프라인, 50MP 전량 합성,
   동영상 **실시간 모델 추론**(GL 톤 체인까지만 구현), OEM급 프레임 타이밍 재정렬식 EIS.
   전체 목록은 `docs/04-구현상태.md`.
6. **수평 잠금은 실험적**: 프리뷰/후처리 셰이더 레벨 보정이며 정지 이미지 원본은 건드리지 않는다.

## 문서

| 문서 | 내용 |
|---|---|
| `docs/01-개발환경-조사.md` | 환경 조사 결과와 이 환경의 한계 |
| `docs/02-하드웨어-API-검증.md` | Flip 6 하드웨어 스펙, RAW/커버화면/Flex/NPU 지원 범위 조사 |
| `docs/03-아키텍처.md` | 모듈 구조, 스레딩, "가짜 UI 금지" 구현 방식, 네이티브 계층 |
| `docs/04-구현상태.md` | 단계별 구현/미구현, 알려진 문제, 테스트 결과 |
| `docs/05-기기테스트-체크리스트.md` | 실기기 검증 절차(명령·기대값·기록 양식) |
| `docs/06-로컬PC-빌드환경.md` | 실제 작업 PC(Windows)에서 빌드가 되게 만든 조치와 실측 결과 |
| `docs/07-워크스페이스-무결성-분석.md` | 스냅샷 저장 실패 원인 규명, PC 사본과의 MD5 무결성 대조, 실제 누락 항목 |

## 모듈 구성

```
app              Compose UI (2가지 디자인, Flex Mode, AI 시트, 설정)
core/camera      Camera2 엔진 + capability probe + 수동 설정 정규화 + 프리뷰 기하
core/native      C++17 코어(노출/클램프/WB/히스토그램 + 디노이즈/샤프닝/업스케일/톤) + JNI
core/ai          LiteRT 래퍼, 백엔드 프로브, 모델 관리, 장면 분석, 보정 파이프라인, 작업 큐
core/storage     MediaStore 저장(IS_PENDING) + 사이드카 메타데이터 인덱스
core/common      공용 모델(렌즈/촬영/메타데이터/로그)
```

## 테스트 현황 (샌드박스 실측)

* JVM 단위 테스트 **44개 통과 / 실패 0** (camera 37 + ai 6 + 기타 1)
* C++ 호스트 테스트 **4종 통과** (수학/노출/WB/히스토그램, 이미지 처리 13개, 멀티프레임 13개, 비디오 루마 연산)
* `:app:assembleDebug` 43.9 MB · `:app:assembleRelease` **35.0 MB (v1.0.0)** 성공
* `lib/arm64-v8a/`: `libaipro_native.so` + LiteRT(`libLiteRt.so`, `liblitert_jni.so`, GL 가속기) 포함
