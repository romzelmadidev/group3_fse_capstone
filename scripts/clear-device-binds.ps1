# Clear all device bindings and active sessions across all accounts in Redis
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "   AuraBank: Resetting All Device Bindings & Auth  " -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

# 1. Clear primary device bindings
Write-Host "`n[1/4] Clearing primary device bindings (auth:primary-device:*)..." -ForegroundColor Yellow
docker exec redis-cache sh -c "redis-cli --scan --pattern 'auth:primary-device:*' | xargs -r redis-cli del"

# 2. Clear registered user devices
Write-Host "[2/4] Clearing registered devices (auth:user-devices:*)..." -ForegroundColor Yellow
docker exec redis-cache sh -c "redis-cli --scan --pattern 'auth:user-devices:*' | xargs -r redis-cli del"

# 3. Clear active user sessions
Write-Host "[3/4] Clearing user sessions (auth:user-sessions:* & session:*)..." -ForegroundColor Yellow
docker exec redis-cache sh -c "redis-cli --scan --pattern 'auth:user-sessions:*' | xargs -r redis-cli del"
docker exec redis-cache sh -c "redis-cli --scan --pattern 'session:*' | xargs -r redis-cli del"

# 4. Clear token families and refresh tokens
Write-Host "[4/4] Clearing refresh tokens and token families..." -ForegroundColor Yellow
docker exec redis-cache sh -c "redis-cli --scan --pattern 'refresh_token:*' | xargs -r redis-cli del"
docker exec redis-cache sh -c "redis-cli --scan --pattern 'token_family:*' | xargs -r redis-cli del"

Write-Host "`nAll device bindings and sessions have been reset in Redis." -ForegroundColor Green
Write-Host "To complete the refresh on client devices (browsers):" -ForegroundColor White
Write-Host "  1. Open DevTools (F12) in Chrome / Edge." -ForegroundColor Gray
Write-Host "  2. Go to Application -> Storage -> Click 'Clear site data' (or run 'localStorage.clear()' in Console)." -ForegroundColor Gray
Write-Host "  3. Log in again. The device will be bound as the primary device." -ForegroundColor Gray
