# Ghar Watch — link the watch to Supabase (run once, or again if the watch shows "LOGIN" errors).
# Logs in on the LAPTOP, then sends only a refresh token to the watch over ADB. Your password never goes to the watch.
#
# First time: copy ghar.local.example.json to ghar.local.json and fill url / key / email (no password in it).
# Run:  powershell -ExecutionPolicy Bypass -File .\push_config.ps1

$ErrorActionPreference = "Stop"
$cfgPath = Join-Path $PSScriptRoot "ghar.local.json"
if (-not (Test-Path $cfgPath)) { throw "Missing $cfgPath - copy ghar.local.example.json and fill it in." }
$cfg = Get-Content $cfgPath -Raw | ConvertFrom-Json

$sec = Read-Host "Supabase login password for $($cfg.email)" -AsSecureString
$pw  = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))

$login = Invoke-RestMethod -Method Post -Uri "$($cfg.url)/auth/v1/token?grant_type=password" `
  -Headers @{ apikey = $cfg.key } -ContentType "application/json" `
  -Body (@{ email = $cfg.email; password = $pw } | ConvertTo-Json)
$pw = $null
Write-Host "Login OK"

$devices = (adb devices) -join "`n"
if ($devices -notmatch "`tdevice") { throw "No watch connected. Run: adb connect <ip>:<port>" }

$out = adb shell am broadcast -n dev.ghar.watch/.system.ConfigReceiver `
  --es url "'$($cfg.url)'" --es key "'$($cfg.key)'" --es person "'$($cfg.person)'" --es refresh "'$($login.refresh_token)'"
Write-Host $out
if (($out -join " ") -match "OK device=") {
  Write-Host "Watch linked. It will register tracking and start a first upload now." -ForegroundColor Green
} else {
  Write-Host "Watch did not confirm. Is Ghar Watch installed? Open it once, then re-run." -ForegroundColor Yellow
}
