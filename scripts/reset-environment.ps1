<#
.SYNOPSIS
  Refreshes the local AuraBank dev environment to a clean slate. Accounts are NEVER deleted.

.DESCRIPTION
  Oracle (single transaction, rolled back on any error)
    - deletes transactions, status history, GL ledger, reversals, outbox events,
      notifications, push tokens, EOD snapshots, interest accruals, fees, COB log
    - zeroes GL balances
    - resets balances: team accounts -> 1,000,000, nostro -> 5,000,000, holds -> 0
    - KYC: deletes submissions, sets users back to PENDING (nostro stays VERIFIED)
    - unlocks users/admins/accounts and clears failed-login counters
    - KEEPS users, admin_users, accounts, balance rows, participant banks,
      GL chart, password and PIN hashes
  Redis   : FLUSHALL (sessions, refresh tokens, device bindings, JTI blacklist,
            idempotency keys, rate limits, KYC review queue, balance cache)
  Azurite : deletes blobs in kyc-vault (the container itself is kept)
  MailHog : deletes captured emails / OTPs
  Optional:
    -IncludeAudit  truncates the Postgres audit vault, clears compliance-vault blobs
    -ClearApp      wipes local data of the AuraBank app on the connected Android device

.EXAMPLE
  .\scripts\reset-environment.ps1 -DryRun     # shows what would change, commits nothing
  .\scripts\reset-environment.ps1             # asks for confirmation
  .\scripts\reset-environment.ps1 -Yes -ClearApp
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [switch]$Yes,
    [switch]$IncludeAudit,
    [switch]$ClearApp
)

$ErrorActionPreference = 'Stop'

# Dev-only credentials (same values as infrastructure/docker-compose.yml)
$OracleConn    = 'fse_user/fse_password@//localhost:1521/XEPDB1'
$AzAccount     = 'devstoreaccount1'
$AzKey         = 'Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw=='
$AppId         = 'com.group3.aurabank.aurabank_app'
$TeamBalance   = 1000000
$NostroBalance = 5000000

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Info($msg) { Write-Host "    $msg" -ForegroundColor Gray }

function Test-Container($name) {
    (docker ps --filter "name=^$name$" --format '{{.Names}}') -eq $name
}

# --------------------------------------------------------------------------
# Azurite helper: Shared Key signed request (no SDK / az CLI needed)
# --------------------------------------------------------------------------
function Invoke-Azurite {
    param([string]$Method, [string]$Container, [string]$Blob = '', [hashtable]$Query = @{})
    $date = [DateTime]::UtcNow.ToString('R')
    $version = '2020-04-08'
    $path = "/$AzAccount/$Container" + $(if ($Blob) { "/$Blob" } else { '' })
    $canonRes = "/$AzAccount$path"
    foreach ($k in ($Query.Keys | Sort-Object)) { $canonRes += "`n$($k.ToLower()):$($Query[$k])" }
    # VERB + 11 empty standard header slots + canonical headers + canonical resource
    $toSign = $Method + "`n" + ("`n" * 11) + "x-ms-date:$date`nx-ms-version:$version`n$canonRes"
    $hmac = New-Object System.Security.Cryptography.HMACSHA256 (, [Convert]::FromBase64String($AzKey))
    $sig = [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($toSign)))
    $qs = if ($Query.Count) { '?' + (($Query.Keys | ForEach-Object { "$_=$($Query[$_])" }) -join '&') } else { '' }
    Invoke-WebRequest -Uri "http://localhost:10000$path$qs" -Method $Method -UseBasicParsing -TimeoutSec 15 -Headers @{
        'x-ms-date'     = $date
        'x-ms-version'  = $version
        'Authorization' = "SharedKey ${AzAccount}:$sig"
    }
}

function Get-BlobNames($container) {
    $names = @()
    $marker = ''
    do {
        $q = @{ restype = 'container'; comp = 'list' }
        if ($marker) { $q['marker'] = $marker }
        $r = Invoke-Azurite -Method GET -Container $container -Query $q
        $xml = [xml]($r.Content.TrimStart([char]0xFEFF))
        $names += @($xml.EnumerationResults.Blobs.Blob | Where-Object { $_ } | ForEach-Object { $_.Name })
        $marker = [string]$xml.EnumerationResults.NextMarker
    } while ($marker)
    $names
}

function Clear-Container($container) {
    try { $names = @(Get-BlobNames $container) } catch {
        Info "container '$container' not reachable, skipped ($($_.Exception.Message))"; return
    }
    if ($DryRun) { Info "would delete $($names.Count) blob(s) from '$container'"; return }
    foreach ($n in $names) { Invoke-Azurite -Method DELETE -Container $container -Blob $n | Out-Null }
    Info "deleted $($names.Count) blob(s) from '$container'"
}

# --------------------------------------------------------------------------
# Preflight
# --------------------------------------------------------------------------
Step 'Preflight'
foreach ($c in 'oracle-xe-master', 'redis-cache') {
    if (-not (Test-Container $c)) { throw "Required container '$c' is not running. Start the stack first." }
}
$hasAzurite  = Test-Container 'azurite-storage'
$hasMailhog  = Test-Container 'mailhog-smtp'
$hasPostgres = Test-Container 'postgres-audit-vault'
Info "oracle-xe-master, redis-cache: running"
if (-not $hasAzurite)  { Write-Host '    azurite-storage not running: blob cleanup will be skipped' -ForegroundColor Yellow }
if (-not $hasMailhog)  { Write-Host '    mailhog-smtp not running: mail cleanup will be skipped' -ForegroundColor Yellow }
if ($IncludeAudit -and -not $hasPostgres) { throw "-IncludeAudit needs 'postgres-audit-vault' running." }

if ($DryRun) {
    Write-Host "`nDRY RUN: nothing will be committed or deleted." -ForegroundColor Yellow
} elseif (-not $Yes) {
    Write-Host "`nThis wipes ALL transactions, sessions, devices and KYC data and resets balances." -ForegroundColor Yellow
    Write-Host 'Accounts and users are kept.' -ForegroundColor Yellow
    if ($IncludeAudit) { Write-Host 'The immutable Postgres audit vault will ALSO be truncated.' -ForegroundColor Red }
    if ((Read-Host "Type RESET to continue") -ne 'RESET') { Write-Host 'Aborted.'; exit 1 }
}

# --------------------------------------------------------------------------
# 1. Oracle
# --------------------------------------------------------------------------
Step 'Oracle: reset ledger, balances, KYC and lockouts (accounts kept)'
$finish = if ($DryRun) { 'rollback' } else { 'commit' }
$sql = @"
whenever sqlerror exit sql.sqlcode rollback
set pagesize 100 linesize 160 feedback off heading on
column what format a34
prompt -- BEFORE
select 'accounts' what, count(*) n from accounts union all
select 'users', count(*) from users union all
select 'transactions', count(*) from transactions union all
select 'outbox_events', count(*) from outbox_events union all
select 'notifications', count(*) from notifications union all
select 'kyc_submissions', count(*) from kyc_submissions union all
select 'users kyc<>PENDING', count(*) from users where kyc_status <> 'PENDING';

delete from reversal_requests;
delete from transaction_status_history;
delete from gl_ledger;
delete from transactions;
delete from outbox_events;
delete from notifications;
delete from device_push_tokens;
delete from eod_balance_snapshots;
delete from interest_accruals;
delete from uncollected_fees;
delete from cob_batch_log;
update gl_balances set total_debit = 0, total_credit = 0, net_balance = 0;

update balance_master
   set balance_amount = case when account_id = 'ACC-NOSTRO-BANK-B' then $NostroBalance else $TeamBalance end,
       hold_amount = 0;
update accounts set status = 'ACTIVE' where status <> 'ACTIVE';

delete from kyc_submissions;
update users set kyc_status = 'PENDING', kyc_review_reason = null where user_id <> 'usr-system-nostro';
update users set status = 'ACTIVE', failed_login_attempts = 0, last_login_at = null;
update admin_users set status = 'ACTIVE', failed_login_attempts = 0, last_login_at = null;
update system_dates set status = 'ONLINE', posting_window_open = 1;

prompt -- AFTER (this session)
select 'accounts' what, count(*) n from accounts union all
select 'users', count(*) from users union all
select 'transactions', count(*) from transactions union all
select 'outbox_events', count(*) from outbox_events union all
select 'notifications', count(*) from notifications union all
select 'kyc_submissions', count(*) from kyc_submissions union all
select 'users kyc<>PENDING', count(*) from users where kyc_status <> 'PENDING' union all
select 'balance rows', count(*) from balance_master union all
select 'sum(balance_amount)', sum(balance_amount) from balance_master;
$finish;
exit
"@
$sql | docker exec -i oracle-xe-master sqlplus -s $OracleConn
if ($LASTEXITCODE -ne 0) { throw "Oracle reset failed (exit $LASTEXITCODE). Transaction was rolled back." }
if ($DryRun) { Info 'rolled back (dry run)' } else { Info 'committed' }

# --------------------------------------------------------------------------
# 2. Redis: sessions, tokens, device bindings, caches, KYC review queue
# --------------------------------------------------------------------------
Step 'Redis: revoke sessions, reset device bindings, clear caches'
Info "keys currently stored: $(docker exec redis-cache redis-cli dbsize)"
if ($DryRun) {
    Info 'would run FLUSHALL'
} else {
    docker exec redis-cache redis-cli flushall | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Redis FLUSHALL failed.' }
    Info "flushed (dbsize now $(docker exec redis-cache redis-cli dbsize))"
}

# --------------------------------------------------------------------------
# 3. Azurite: KYC documents
# --------------------------------------------------------------------------
Step 'Azurite: clear KYC documents'
if ($hasAzurite) {
    Clear-Container 'kyc-vault'
    if ($IncludeAudit) { Clear-Container 'compliance-vault' }
} else { Info 'skipped' }

# --------------------------------------------------------------------------
# 4. MailHog: captured emails / OTPs
# --------------------------------------------------------------------------
Step 'MailHog: clear captured emails'
if ($hasMailhog) {
    try {
        $total = (Invoke-RestMethod -Uri 'http://localhost:8025/api/v2/messages?limit=1' -TimeoutSec 5).total
        if ($DryRun) { Info "would delete $total message(s)" }
        else {
            Invoke-RestMethod -Method Delete -Uri 'http://localhost:8025/api/v1/messages' -TimeoutSec 5 | Out-Null
            Info "deleted $total message(s)"
        }
    } catch { Write-Host "    MailHog API unreachable, skipped ($($_.Exception.Message))" -ForegroundColor Yellow }
} else { Info 'skipped' }

# --------------------------------------------------------------------------
# 5. Postgres audit vault (opt-in: it is append-only by design)
# --------------------------------------------------------------------------
if ($IncludeAudit) {
    Step 'Postgres: truncate audit vault'
    $pgFinish = if ($DryRun) { 'ROLLBACK' } else { 'COMMIT' }
    $pg = @"
BEGIN;
TRUNCATE ledger_mutation_audit, transaction_status_audit, audit_block_anchor,
         compliance_filings, eod_reports_metadata, failed_transaction_audit;
$pgFinish;
"@
    $pg | docker exec -i postgres-audit-vault psql -U audit_user -d banking_audit -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) { throw 'Postgres truncate failed.' }
}

# --------------------------------------------------------------------------
# 6. Phone: wipe local app data (tokens, device id) so it behaves like a fresh install
# --------------------------------------------------------------------------
if ($ClearApp) {
    Step "Android: clear app data ($AppId)"
    $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path $adb)) { $adb = 'adb' }
    $connected = (& $adb devices | Select-String "`tdevice$")
    if (-not $connected) { Write-Host '    no device connected, skipped' -ForegroundColor Yellow }
    elseif ($DryRun) { Info "would run: adb shell pm clear $AppId" }
    else { & $adb shell pm clear $AppId }
}

Write-Host ''
if ($DryRun) {
    Write-Host 'Dry run finished. Nothing was changed.' -ForegroundColor Yellow
} else {
    Write-Host 'Environment reset. Accounts kept; everyone must log in again and re-bind their device.' -ForegroundColor Green
    if (-not $ClearApp) {
        Write-Host 'Web: clear site data in the browser. Phone: re-run with -ClearApp or clear app storage.' -ForegroundColor Gray
    }
}
