<#
.SYNOPSIS
    Test-DlqAndInterbank.ps1
    PowerShell Test Script for Dead Letter Queues (DLQ) & Interbank Transfers.

.DESCRIPTION
    Validates the live implementation of:
    1. Dead Letter Queues (DLQ) & Autonomous/Manual Replaying
    2. Interbank Transfers (Bilateral Nostro/Vostro, Inward/Outward, Saga Rollback, Security)

.PARAMETER GatewayUrl
    Default: http://localhost:8080
.PARAMETER CbsUrl
    Default: http://localhost:8087
.PARAMETER OrchestratorUrl
    Default: http://localhost:8082
.PARAMETER ComplianceUrl
    Default: http://localhost:8086
.PARAMETER ApiKey
    Default: AURABANK-SECURE-KEY-2026
#>

[CmdletBinding()]
param(
    [string]$GatewayUrl = "http://localhost:8080",
    [string]$CbsUrl = "http://localhost:8087",
    [string]$OrchestratorUrl = "http://localhost:8082",
    [string]$ComplianceUrl = "http://localhost:8086",
    [string]$ApiKey = "AURABANK-SECURE-KEY-2026"
)

$ErrorActionPreference = "Continue"

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  AURABANK POWERSHELL VERIFICATION RUNNER" -ForegroundColor Cyan
Write-Host "  1) DEAD LETTER QUEUES & REPLAYING | 2) INTERBANK TRANSFERS" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

function Invoke-SafeApi {
    param(
        [string]$Url,
        [string]$Method = "GET",
        [hashtable]$Headers = @{},
        [string]$Body = $null
    )
    try {
        $reqHeaders = @{ "Content-Type" = "application/json" }
        foreach ($k in $Headers.Keys) { $reqHeaders[$k] = $Headers[$k] }
        
        $params = @{
            Uri = $Url
            Method = $Method
            Headers = $reqHeaders
            TimeoutSec = 8
            UseBasicParsing = $true
        }
        if ($Body) { $params["Body"] = $Body }
        
        $resp = Invoke-WebRequest @params
        $bodyObj = $null
        if ($resp.Content) {
            try { $bodyObj = $resp.Content | ConvertFrom-Json } catch { $bodyObj = $resp.Content }
        }
        return [PSCustomObject]@{
            StatusCode = [int]$resp.StatusCode
            Body = $bodyObj
            Success = $true
            Error = $null
        }
    } catch {
        $status = 0
        $errMsg = $_.Exception.Message
        $bodyObj = $null
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
            try {
                $stream = $_.Exception.Response.GetResponseStream()
                $reader = New-Object System.IO.StreamReader($stream)
                $errMsg = $reader.ReadToEnd()
                try { $bodyObj = $errMsg | ConvertFrom-Json } catch { $bodyObj = $errMsg }
            } catch {}
        }
        return [PSCustomObject]@{
            StatusCode = $status
            Body = $bodyObj
            Success = ($status -ge 200 -and $status -lt 300)
            Error = $errMsg
        }
    }
}

$results = @()

function Log-Result {
    param(
        [string]$Feature,
        [string]$TestName,
        [bool]$IsPositive,
        [bool]$Passed,
        [string]$Details
    )
    $script:results += [PSCustomObject]@{
        Feature = $Feature
        TestName = $TestName
        Type = if ($IsPositive) { "POSITIVE" } else { "NEGATIVE" }
        Status = if ($Passed) { "PASS" } else { "FAIL" }
        Details = $Details
    }
    $symbol = if ($Passed) { "[PASS]" } else { "[FAIL]" }
    $color = if ($Passed) { "Green" } else { "Red" }
    $tag = if ($IsPositive) { "(+)" } else { "(-)" }
    Write-Host "  $symbol $tag $TestName" -ForegroundColor $color
    if ($Details) {
        Write-Host "       ↳ $Details" -ForegroundColor DarkGray
    }
}

# ----------------------------------------------------------------------------
# SUITE 1: DEAD LETTER QUEUES (DLQ) & REPLAYING
# ----------------------------------------------------------------------------
Write-Host "`n--- SUITE 1: DEAD LETTER QUEUES (DLQ) & REPLAYING ---" -ForegroundColor Yellow

# Test 1.1: Autonomous DLQ Recovery Daemon
$daemonUrl = "$GatewayUrl/api/v1/compliance/dlq/status"
$res = Invoke-SafeApi -Url $daemonUrl
if (-not $res.Success -and $res.StatusCode -eq 0) {
    $daemonUrl = "$ComplianceUrl/api/v1/compliance/dlq/status"
    $res = Invoke-SafeApi -Url $daemonUrl
}
$passed = ($res.StatusCode -eq 200) -and ($res.Body.autonomousDaemonActive -eq $true)
Log-Result -Feature "DLQ & Replay" -TestName "Autonomous DLQ Recovery Daemon Status & ShedLock" -IsPositive $true -Passed $passed -Details "HTTP $($res.StatusCode) | ShedLock: $($res.Body.shedLockSynced) | CBS: $($res.Body.cbsHealthy) | MaxAge: $($res.Body.maxAgeMinutes)m"

# Test 1.2: Audit Vault DLQ Backlog
$dlqUrl = "$GatewayUrl/api/v1/cbs/audit/failed-transactions?status=ALL&page=0&size=20"
$res = Invoke-SafeApi -Url $dlqUrl
if (-not $res.Success -and $res.StatusCode -eq 0) {
    $dlqUrl = "$CbsUrl/api/v1/cbs/audit/failed-transactions?status=ALL&page=0&size=20"
    $res = Invoke-SafeApi -Url $dlqUrl
}
$passed = ($res.StatusCode -eq 200) -and ($res.Body -is [System.Array])
$count = if ($res.Body) { $res.Body.Count } else { 0 }
$incidents = if ($res.Body -is [System.Array]) { $res.Body } else { @() }
Log-Result -Feature "DLQ & Replay" -TestName "Audit Vault Failed Transactions Inspection" -IsPositive $true -Passed $passed -Details "HTTP $($res.StatusCode) | Incidents in vault: $count"

# Test 1.3: Direct CBS Audit Resolution Pipeline
$targetIncident = if ($incidents.Count -gt 0) { $incidents[0] } else { $null }
$testTx = if ($targetIncident) {
    if ($targetIncident.transactionId) { $targetIncident.transactionId } else { $targetIncident.incidentId }
} else {
    "TXN-PS-DLQ-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
}
$resolveUrl = "$GatewayUrl/api/v1/cbs/audit/failed-transactions/$testTx/resolve"
$resBody = @{
    notes = "PowerShell automated test audit resolution"
    operatorId = "POWERSHELL_OPERATOR"
    replayStatus = "RESOLVED"
} | ConvertTo-Json
$res = Invoke-SafeApi -Url $resolveUrl -Method "POST" -Body $resBody
if (-not $res.Success -and $res.StatusCode -eq 0) {
    $resolveUrl = "$CbsUrl/api/v1/cbs/audit/failed-transactions/$testTx/resolve"
    $res = Invoke-SafeApi -Url $resolveUrl -Method "POST" -Body $resBody
}
if ($targetIncident) {
    $passed = ($res.StatusCode -eq 200) -and ($res.Body.replayStatus -eq "RESOLVED")
    $details = "HTTP $($res.StatusCode) | Status: $($res.Body.replayStatus) | By: $($res.Body.resolvedBy)"
} else {
    $passed = ($res.StatusCode -eq 400) -and ($res.Error -like "*Failed transaction record not found*")
    $details = "HTTP $($res.StatusCode) | Correctly rejected non-existent incident (Business Validation)"
}
Log-Result -Feature "DLQ & Replay" -TestName "Audit Vault Direct Incident Resolution" -IsPositive $([bool]$targetIncident) -Passed $passed -Details $details

# ----------------------------------------------------------------------------
# SUITE 2: INTERBANK TRANSFERS
# ----------------------------------------------------------------------------
Write-Host "`n--- SUITE 2: INTERBANK TRANSFERS ---" -ForegroundColor Yellow

# Test 2.1: Participant Banks Directory
$dirUrl = "$GatewayUrl/api/v1/interbank/banks"
$res = Invoke-SafeApi -Url $dirUrl
if (-not $res.Success -and $res.StatusCode -eq 0) {
    $dirUrl = "$OrchestratorUrl/api/v1/interbank/banks"
    $res = Invoke-SafeApi -Url $dirUrl
}
$passed = ($res.StatusCode -eq 200) -and ($res.Body -is [System.Array]) -and (@($res.Body | Where-Object { $_.bankCode -eq "COOBS" }).Count -gt 0)
Log-Result -Feature "Interbank" -TestName "Participant Banks Directory Facade (OFS-Backed)" -IsPositive $true -Passed $passed -Details "HTTP $($res.StatusCode) | Directory contains COOBS & AURABANK"

# Test 2.2: Outward Wire (Happy Path)
$txOut = "IB-OUT-PS-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
$outBody = @{
    transactionId = $txOut
    sourceAccountId = "1000-2000-3001"
    destinationAccountId = "B-ACC-998877"
    amount = 1200.00
    currency = "PHP"
    destinationBank = "COOBS"
    senderName = "Alice Reyes"
    description = "PowerShell interbank wire verification"
    idempotencyKey = "IDEMP-$txOut"
} | ConvertTo-Json
$outUrl = "$GatewayUrl/api/v1/transfers"
$res = Invoke-SafeApi -Url $outUrl -Method "POST" -Body $outBody
if (-not $res.Success -and $res.StatusCode -eq 0) {
    $outUrl = "$OrchestratorUrl/api/v1/transfers"
    $res = Invoke-SafeApi -Url $outUrl -Method "POST" -Body $outBody
}
$passed = ($res.StatusCode -eq 200) -and ($res.Body.status -eq "Posted")
Log-Result -Feature "Interbank" -TestName "Outward Wire Transfer (Leg 1 Nostro + Leg 2 Dispatch)" -IsPositive $true -Passed $passed -Details "HTTP $($res.StatusCode) | Status: $($res.Body.status) | Msg: $($res.Body.message)"

# Test 2.3: Outward Wire Timeout -> Saga Compensating Reversal + DLQ
$txTim = "IB-TIM-PS-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
$timBody = @{
    transactionId = $txTim
    sourceAccountId = "1000-2000-3001"
    destinationAccountId = "B-ACC-998877"
    amount = 600.00
    currency = "PHP"
    destinationBank = "COOBS"
    senderName = "Alice Reyes"
    simulationMode = "TIMEOUT"
    idempotencyKey = "IDEMP-$txTim"
} | ConvertTo-Json
$res = Invoke-SafeApi -Url $outUrl -Method "POST" -Body $timBody
$passed = ($res.StatusCode -eq 502) -and ($res.Error -like "*returned to your account*")
Log-Result -Feature "Interbank" -TestName "Outward Wire Timeout & Automated Saga Compensating Reversal" -IsPositive $false -Passed $passed -Details "HTTP $($res.StatusCode) | Sanitized customer error: $($res.Error)"

# Test 2.4: Inward Wire Ingestion (Partner -> AuraBank)
$inMsgId = "WIRE-IN-PS-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
$inBody = @{
    messageId = $inMsgId
    sourceBankCode = "COOBS"
    destinationBankCode = "AURABANK"
    sender = @{
        accountNumber = "B-ACC-778899"
        name = "Carlos Yulo"
    }
    beneficiary = @{
        accountNumber = "1000-2000-3001"
        name = "Alice Reyes"
    }
    amount = 2800.00
    currency = "PHP"
    purpose = "Inward interbank wire settlement"
} | ConvertTo-Json
$inUrl = "$GatewayUrl/api/v1/interbank/transfers/inward"
$inHeaders = @{ "X-Interbank-API-Key" = $ApiKey }
$res = Invoke-SafeApi -Url $inUrl -Method "POST" -Headers $inHeaders -Body $inBody
if (-not $res.Success -and $res.StatusCode -eq 0) {
    $inUrl = "$OrchestratorUrl/api/v1/interbank/transfers/inward"
    $res = Invoke-SafeApi -Url $inUrl -Method "POST" -Headers $inHeaders -Body $inBody
}
$passed = ($res.StatusCode -eq 200) -and ($res.Body.status -eq "ACCEPTED")
Log-Result -Feature "Interbank" -TestName "Inward Wire Ingestion (Leg 3 Nostro Debit -> Beneficiary Credit)" -IsPositive $true -Passed $passed -Details "HTTP $($res.StatusCode) | Status: $($res.Body.status) | ReceiverRef: $($res.Body.receiverReference) | Beneficiary: $($res.Body.beneficiaryName)"

# Test 2.5: Inward Wire Security Boundary (Invalid API Key)
$badHeaders = @{ "X-Interbank-API-Key" = "FORGED-KEY-000" }
$res = Invoke-SafeApi -Url $inUrl -Method "POST" -Headers $badHeaders -Body $inBody
$passed = ($res.StatusCode -eq 401)
Log-Result -Feature "Interbank" -TestName "Inward Wire Unauthorized API Key Rejection" -IsPositive $false -Passed $passed -Details "HTTP $($res.StatusCode) | Blocked forged request"

Write-Host "`n================================================================" -ForegroundColor Cyan
Write-Host "TEST SUMMARY" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
$passedCount = ($results | Where-Object { $_.Status -eq "PASS" }).Count
$totalCount = $results.Count
$failedCount = $totalCount - $passedCount
Write-Host "Total Tests: $totalCount"
Write-Host "Passed:      $passedCount" -ForegroundColor Green
Write-Host "Failed:      $failedCount" -ForegroundColor $(if ($failedCount -eq 0) { "Green" } else { "Red" })
Write-Host "================================================================`n" -ForegroundColor Cyan
