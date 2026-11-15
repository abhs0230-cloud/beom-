# 숨케어 (SumCare) — 차 안 공기 안심 비서

창문이 열리기 전에 바깥 공기를 먼저 읽는 Pleos Connect 차량용 앱입니다.
현대자동차그룹 차량용 앱 개발 공모전 출품작.

- **APK**: [`apk/SumCare-v1.0-debug.apk`](apk/SumCare-v1.0-debug.apk) (Pleos Connect Emulator v2.1.0에서 설치·동작 확인)
- **시연 영상**: [`demo/sumcare-demo.mp4`](demo/sumcare-demo.mp4) (약 90초, 에뮬레이터 화면 녹화)

## 주요 기능
1. **출발 브리핑**: 기어가 D로 바뀌면 바깥 미세먼지를 음성으로 알려 줍니다.
2. **창문 열림 경고**: 미세먼지가 '나쁨' 이상인데 창문이 열리기 시작하면 즉시 음성으로 경고합니다.
   "잠깐만요! 오늘 미세먼지가 높아 내기순환을 권장드립니다."
3. **원터치 보호**: 버튼 한 번으로 창문 닫기, 내기순환, 공기청정을 한꺼번에 켭니다.
4. **환기 권장**: 공기가 좋은 날 창문을 열면 환기하기 좋다고 알려 줍니다.
5. **음성 명령**: "내기모드 켜줘", "공기청정 켜줘", "창문 닫아줘", "외기로 바꿔줘" (Pleos SpeechToText SDK)
6. **공기 흐름 애니메이션**: 바깥 미세먼지 양, 열린 창문으로 들어오는 먼지·바람, 내기순환과 공기청정 상태를 위에서 본 차 그림으로 보여 줍니다.

## 사용한 SDK
| 기능 | SDK / API |
|---|---|
| 기어·창문·공조 | Pleos Vehicle SDK 2.3.2 |
| 현재 위치 | Pleos NaviHelper SDK 3.0.5 |
| 음성 안내 | Pleos TextToSpeech SDK 3.3.1 (ON_DEVICE) |
| 음성 명령 | Pleos SpeechToText SDK 3.3.1 (ON_DEVICE) |
| 미세먼지 | 에어코리아 대기오염정보 API (공공데이터포털) |

## 실행 방법
- 개발 환경: Android Studio 2026.2, compileSdk/targetSdk 34, minSdk 28, Gradle 9.8.2, AGP 9.5.1
- 테스트 환경: Pleos Connect Emulator v2.1.0 (API 34) + Pleos Connect Virtual Console
- 빌드: `gradlew.bat assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- 빌드 없이 바로 설치: 에뮬레이터를 켠 뒤 `adb install apk/SumCare-v1.0-debug.apk` → 앱 목록에서 "숨케어" 실행
- 또는 Android Studio에서 프로젝트를 열고 Run
- 필요한 설정: 처음 실행 시 마이크 권한 허용(음성 명령용). 차량 상태(기어·창문·공조)는 Virtual Console(`adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088/)에서 바꿉니다.

### 미세먼지 실데이터
`local.properties`에 공공데이터포털 인증키를 넣으면 에어코리아 실시간 값을 사용합니다. 키가 없으면 데모 모드로 동작합니다.
```
AIRKOREA_KEY=발급받은_인증키
```

### 시연 (데모 모드)
- 바깥 공기 값 바꾸기: `adb shell am broadcast -a com.sumcare.app.DEMO --es grade good|normal|bad|verybad`
- 음성 명령 시연 입력: `adb shell am broadcast -a com.sumcare.app.DEMO --es voice recirc_on`
- 시연 영상 만들기 (Virtual Console http://127.0.0.1:8088/ 을 브라우저에 연 상태에서)
  1. `powershell -ExecutionPolicy Bypass -File demo\record-demo.ps1` → 화면 녹화 `demo\raw-demo.webm` + 음성 타이밍 `demo\timeline.txt`
  2. `powershell -ExecutionPolicy Bypass -File demo\make-video.ps1` → `demo\sumcare-demo.mp4`
     (에뮬레이터 녹음은 작고 잡음이 있어, 같은 안내 문장을 Windows 한국어 음성으로 다시 입힙니다. FFmpeg 필요)

## 알려진 제약 (에뮬레이터 기준)
- 썬루프(ROOF_TOP) 창문은 에뮬레이터에서 지원되지 않아 실차 확장 기능으로 남겨 두었습니다.
- 시트 등받이 각도는 이동 명령은 받지만 값이 0으로 고정되어 사용하지 않았습니다.
