# Watcher script that continuously monitors device connection and auto-restores tunnels
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (!(Test-Path $adb)) {
    $adb = 'adb'
}

$ports = @(8080, 8081, 8082, 8083, 8084, 8085, 8086, 8025, 10000, 10005)

Write-Host '==========================================================' -ForegroundColor Cyan
Write-Host ' ADB Device Connection Auto-Reconnect Monitor Active' -ForegroundColor Cyan
Write-Host ' Keeps port tunnels alive whenever your phone connects/reconnects' -ForegroundColor Cyan
Write-Host ' Press Ctrl+C anytime to stop.' -ForegroundColor Cyan
Write-Host '==========================================================' -ForegroundColor Cyan

$lastState = ''

while ($true) {
    try {
        $rawDevices = & $adb devices
        $hasDevice = $false
        foreach ($line in $rawDevices) {
            if ($line -match '\tdevice$') {
                $hasDevice = $true
                break
            }
        }

        if ($hasDevice) {
            $reversed = & $adb reverse --list | Out-String
            $needsReapply = $false
            foreach ($p in $ports) {
                if ($reversed -notmatch "tcp:$p tcp:$p") {
                    $needsReapply = $true
                    break
                }
            }

            if ($needsReapply) {
                $now = (Get-Date).ToString('HH:mm:ss')
                Write-Host "[$now] Device detected without reverse tunnels! Bridging ports..." -ForegroundColor Yellow
                foreach ($p in $ports) {
                    & $adb reverse tcp:$p tcp:$p | Out-Null
                }
                Write-Host "[$now] All $($ports.Count) ports successfully bridged to device!" -ForegroundColor Green
            }
            $lastState = 'connected'
        } else {
            if ($lastState -ne 'disconnected') {
                $now = (Get-Date).ToString('HH:mm:ss')
                Write-Host "[$now] Device not detected. Waiting for phone to connect..." -ForegroundColor Gray
                $lastState = 'disconnected'
            }
        }
    } catch {
        # ignore transient errors
    }
    Start-Sleep -Seconds 2
}
