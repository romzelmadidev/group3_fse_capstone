# Reverse port forwarding script for Android physical devices over USB
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (!(Test-Path $adb)) {
    $adb = "adb"
}

Write-Host "Setting up ADB reverse port tunnels..." -ForegroundColor Cyan
& $adb reverse tcp:8080 tcp:8080   # Gateway Service
& $adb reverse tcp:8081 tcp:8081   # Account Service
& $adb reverse tcp:8082 tcp:8082   # Transfer Orchestrator
& $adb reverse tcp:8083 tcp:8083   # Notification Service
& $adb reverse tcp:8084 tcp:8084   # Risk Service
& $adb reverse tcp:8085 tcp:8085   # T24 Mock CBS
& $adb reverse tcp:8086 tcp:8086   # Compliance Service
& $adb reverse tcp:8025 tcp:8025   # MailHog Web/API
& $adb reverse tcp:10000 tcp:10000 # Azurite Blob (KYC SAS uploads)
& $adb reverse tcp:10005 tcp:10005 # Azurite Drive

Write-Host "Active reverse tunnels:" -ForegroundColor Green
& $adb reverse --list
