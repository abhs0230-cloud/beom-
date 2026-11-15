# 숨케어 시연 영상 만들기: 녹화 화면 + 깨끗한 한국어 음성(Windows Heami) → MP4
# 준비: record-demo.ps1 실행 결과(raw-demo.webm, timeline.txt), FFmpeg 설치
# 실행: powershell -ExecutionPolicy Bypass -File demo\make-video.ps1
# 결과: demo\sumcare-demo.mp4 (1920x1080, H.264 + AAC)

$ErrorActionPreference = 'Stop'
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
$dir = $PSScriptRoot
$raw = Join-Path $dir 'raw-demo.webm'
$out = Join-Path $dir 'sumcare-demo.mp4'
$work = Join-Path $dir 'audio'
New-Item -ItemType Directory -Force $work | Out-Null
Get-ChildItem $work -Filter *.wav | Remove-Item

# ── 1. 타이밍 읽기 (MARK = 녹화 시작) ──
$lines = Get-Content (Join-Path $dir 'timeline.txt') -Encoding utf8
$mark = $null; $events = @()
foreach ($l in $lines) {
    if ($l -match '^\s*(\d+\.\d+)\s.*SUMCARE\s*:\s*(MARK|SAY|HEARD)(?::\s*(.*))?$') {
        $t = [double]$Matches[1]
        if ($Matches[2] -eq 'MARK') { $mark = $t; continue }
        if ($mark -ne $null) { $events += [pscustomobject]@{ kind = $Matches[2]; t = $t - $mark; text = $Matches[3] } }
    }
}
if ($mark -eq $null) { throw 'timeline.txt 에 MARK 가 없습니다. record-demo.ps1 을 다시 실행하세요.' }

# 운전자 발화는 '듣고 있어요' 화면이 뜬 직후에 들리도록 앞당긴다
foreach ($e in $events) { if ($e.kind -eq 'HEARD') { $e.t = [Math]::Max(0, $e.t - 1.5) } }
# 경고 문장 앞에는 알림음을 넣고 음성을 살짝 뒤로 민다
$chimes = @()
foreach ($e in $events) { if ($e.kind -eq 'SAY' -and $e.text -like '잠깐만요*') { $chimes += $e.t; $e.t += 0.7 } }
# 같은 시각의 운전자 발화와 앱 응답이 겹치지 않게 응답을 뒤로 민다
for ($i = 1; $i -lt $events.Count; $i++) {
    if ($events[$i - 1].kind -eq 'HEARD' -and $events[$i].kind -eq 'SAY') { $events[$i].t = [Math]::Max($events[$i].t, $events[$i - 1].t + 1.9) }
}

# ── 2. 문장별 음성 만들기 ──
Add-Type -AssemblyName System.Speech
$syn = New-Object System.Speech.Synthesis.SpeechSynthesizer
$syn.SelectVoice('Microsoft Heami Desktop')
$syn.Rate = 1
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(24000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$idx = 0
foreach ($e in $events) {
    $idx++
    $wav = Join-Path $work ("{0:D2}.wav" -f $idx)
    $syn.SetOutputToWaveFile($wav, $fmt)
    $syn.Speak(($e.text -replace "'", ''))
    $syn.SetOutputToNull()
    $e | Add-Member -NotePropertyName wav -NotePropertyValue $wav
    $e | Add-Member -NotePropertyName dur -NotePropertyValue (((Get-Item $wav).Length - 44) / 48000.0)
}
$syn.Dispose()

# ── 3. FFmpeg로 합치기 ──
$inputs = @('-i', $raw)
$filters = @(); $labels = @(); $n = 1
for ($i = 0; $i -lt $events.Count; $i++) {
    $e = $events[$i]
    $inputs += @('-i', $e.wav)
    $ms = [int]($e.t * 1000)
    $chain = "[$n`:a]aresample=48000"
    # 다음 문장과 겹치면 조금 빠르게 읽는다
    if ($i + 1 -lt $events.Count) {
        $gap = $events[$i + 1].t - $e.t - 0.25
        if ($e.dur -gt $gap -and $gap -gt 0.5) { $chain += ",atempo={0:F3}" -f [Math]::Min(1.35, $e.dur / $gap) }
    }
    if ($e.kind -eq 'HEARD') { $chain += ',asetrate=48000*0.84,aresample=48000,atempo=1.19' }   # 운전자 목소리 (낮은 톤)
    $chain += ",adelay=$ms|$ms[a$n]"
    $filters += $chain; $labels += "[a$n]"; $n++
}
foreach ($c in $chimes) {
    $ms = [int]($c * 1000)
    $inputs += @('-f', 'lavfi', '-t', '0.6', '-i', 'sine=frequency=880:sample_rate=48000')
    $filters += "[$n`:a]volume=0.25,afade=t=out:st=0.25:d=0.35,adelay=$ms|$ms[a$n]"; $labels += "[a$n]"; $n++
}
$filters += ($labels -join '') + "amix=inputs=$($labels.Count):normalize=0,loudnorm=I=-16:TP=-1.5:LRA=11,apad[aout]"
$filters += '[0:v]fps=30,scale=1920:1080:flags=lanczos,format=yuv420p[vout]'
$graph = Join-Path $work 'graph.txt'
[IO.File]::WriteAllText($graph, ($filters -join ";`n"), (New-Object Text.UTF8Encoding($false)))

& ffmpeg -y -hide_banner -loglevel error @inputs -/filter_complex $graph -map '[vout]' -map '[aout]' `
    -c:v libx264 -preset medium -crf 22 -c:a aac -b:a 192k -movflags +faststart -shortest $out
if ($LASTEXITCODE -ne 0) { throw "ffmpeg 실패 ($LASTEXITCODE)" }

$events | ForEach-Object { '{0,6:F1}s  {1,-5} {2}' -f $_.t, $_.kind, $_.text }
Write-Host "saved: $out"
