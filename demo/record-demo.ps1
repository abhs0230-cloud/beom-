# 숨케어 시연 영상 자동 녹화 스크립트
# 준비: Pleos Connect 에뮬레이터 실행 + 숨케어 앱 설치
#       + 브라우저에서 Virtual Console(http://127.0.0.1:8088/)을 열어 둘 것
# 실행: powershell -ExecutionPolicy Bypass -File demo\record-demo.ps1
# 결과: demo\raw-demo.webm (화면), demo\timeline.txt (음성 타이밍) → make-video.ps1 로 최종 영상 생성

$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$out = Join-Path $PSScriptRoot 'raw-demo.webm'
$timeline = Join-Path $PSScriptRoot 'timeline.txt'

& $adb forward tcp:8088 tcp:8088 | Out-Null

# Virtual Console은 접속 세션이 3초 이상 끊기면 차량 조작을 거부한다 → 녹화 내내 세션 신호를 보낸다
$heartbeat = Start-Job {
    while ($true) {
        try { Invoke-WebRequest 'http://127.0.0.1:8088/browser/session?client=sumcare-demo' -Method Post -UseBasicParsing | Out-Null } catch {}
        Start-Sleep -Milliseconds 800
    }
}
Start-Sleep 2

function Car($query) {
    for ($i = 0; $i -lt 5; $i++) {
        try { Invoke-WebRequest "http://127.0.0.1:8088$query" -Method Put -UseBasicParsing | Out-Null; return }
        catch { Start-Sleep -Milliseconds 500 }
    }
    Write-Warning "Virtual Console 조작 실패: $query"
}
function App([string[]]$extras) { & $adb shell am broadcast -a com.sumcare.app.DEMO @extras | Out-Null }
function Wait($sec) { Start-Sleep -Seconds $sec }

# ── 초기 상태 ──
Car '/vehicle/gear?gear=Park'
Car '/vehicle/body?window_fl=0'
Car '/vehicle/hvac?recirc=false'
Car '/vehicle/hvac-air-cleaner?activated=false'
& $adb shell input keyevent KEYCODE_BACK
& $adb shell am start -S -n com.sumcare.app/.MainActivity | Out-Null
Wait 8
App '--es', 'grade', 'good', '--es', 'caption', 'none'
Car '/vehicle/gear?gear=Park'      # 앱이 켜진 뒤 다시 한 번 P로 맞춘다 (장면 1의 출발 감지용)
Car '/vehicle/body?window_fl=0'
Wait 3
& $adb logcat -c

# ── 녹화 시작 ──
if (Test-Path $out) { Remove-Item $out }
& $adb emu screenrecord start --time-limit 170 $out | Out-Null
App '--ez', 'mark', 'true'
Wait 2

# 장면 1: 공기 좋은 날 → 출발 브리핑, 창문 열면 환기 권장 (바람 애니메이션)
App '--es', 'caption', 's1', '--ez', 'intro', 'true'
Wait 7
Car '/vehicle/gear?gear=Drive'
Wait 8
Car '/vehicle/body?window_fl=60'
Wait 8
Car '/vehicle/body?window_fl=0'
Car '/vehicle/gear?gear=Park'
Wait 2

# 장면 2: 미세먼지 나쁜 날 출발 → 음성 브리핑 + 선택지 (먼지 입자 증가)
App '--es', 'caption', 's2', '--es', 'grade', 'bad'
Wait 4
Car '/vehicle/gear?gear=Drive'
Wait 9
App '--es', 'tap', 'secondary'      # "괜찮아요"
Wait 6

# 장면 3: 창문을 여는 순간 → 먼지 유입 애니메이션 + 즉시 경고 → 원터치 보호
App '--es', 'caption', 's3'
Wait 2
Car '/vehicle/body?window_fl=60'
Wait 10
App '--es', 'tap', 'primary'        # "창문 닫고 내기순환"
Wait 9

# 장면 4: 말로 내기모드 켜기 (음성 명령, 시연 입력)
Car '/vehicle/hvac?recirc=false'
Car '/vehicle/hvac-air-cleaner?activated=false'
App '--es', 'caption', 's4'
Wait 4
App '--es', 'voice', 'recirc_on'    # "내기모드 켜줘"
Wait 10
App '--es', 'caption', 'end'
Wait 5

# ── 녹화 종료 ──
& $adb emu screenrecord stop | Out-Null
Wait 3
Stop-Job $heartbeat; Remove-Job $heartbeat

# 음성 타이밍 기록 (MARK = 녹화 시작 기준점)
& $adb logcat -d -v epoch -s SUMCARE:D | Out-File -Encoding utf8 $timeline
Write-Host "saved: $out"
