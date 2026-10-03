# Stops and removes the ovenlight scheduled task, its binary and its PATH entry.
# Node state, the app list and the log stay, so a reinstall needs no new logins; --purge
# deletes them too, with the stored API credential, the guest records, the feedback
# inbox and the policy backups (the nodes then stay in your tailnet, offline, until you
# remove them in the admin console).
# Run it from an administrator PowerShell, as install.ps1:
#   powershell -ExecutionPolicy Bypass -File .\uninstall.ps1 [--purge]
$ErrorActionPreference = 'Stop'

$task = 'ovenlight'
$support = Join-Path $env:LOCALAPPDATA 'ovenlight'
$binDir = Join-Path $support 'bin'
$bin = Join-Path $binDir 'ovenlight.exe'
# The connector itself; other ovenlight processes, such as an MCP server, aren't it.
function Running {
  Get-CimInstance Win32_Process -Filter "Name = 'ovenlight.exe'" | Where-Object { $_.ExecutablePath -eq $bin -and $_.CommandLine -like '* run *' }
}

function Fail($message) {
  [Console]::Error.WriteLine($message)
  exit 1
}

$me = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not ([Security.Principal.WindowsPrincipal]$me).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  Fail 'uninstall.ps1 needs an administrator PowerShell: right-click PowerShell, choose Run as administrator, and run it again.'
}

# Windows can't delete a binary that runs, such as a coding agent's MCP server.
if (Get-CimInstance Win32_Process -Filter "Name = 'ovenlight.exe'" | Where-Object { $_.ExecutablePath -like "$binDir\*" -and $_.CommandLine -notlike '* run *' }) {
  Fail 'ovenlight is in use outside the connector (by a coding agent''s MCP server, say): close it, then run uninstall.ps1 again'
}

# Never remove another account's connector.
$existing = Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
if ($existing -and (New-Object Security.Principal.NTAccount $existing.Principal.UserId).Translate([Security.Principal.SecurityIdentifier]) -ne $me.User) {
  Fail "the connector here runs as $($existing.Principal.UserId): uninstall it from that account"
}
if ($existing) {
  Disable-ScheduledTask -TaskName $task | Out-Null # so a minute's try doesn't restart it
  Stop-ScheduledTask -TaskName $task
  Unregister-ScheduledTask -TaskName $task -Confirm:$false
}
for ($i = 0; $i -lt 50 -and (Running); $i++) { Start-Sleep -Milliseconds 200 }

# The PATH entry install.ps1 added, kept as written otherwise.
$envKey = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)
$path = [string]$envKey.GetValue('Path', '', 'DoNotExpandEnvironmentNames')
if (($path -split ';') -contains $binDir) {
  $envKey.SetValue('Path', (($path -split ';' | Where-Object { $_ -and $_ -ne $binDir }) -join ';'), 'ExpandString')
}
$envKey.Close()

Remove-Item -Force -ErrorAction SilentlyContinue $bin, (Join-Path $binDir 'ovenlight.old*.exe'), (Join-Path $support 'ovenlight.sock'), (Join-Path $support 'ovenlight.lock')
Write-Output "removed $task"

if ($args -contains '--purge') {
  # The log names guests, devices and your login.
  Remove-Item -Recurse -Force $support
  Write-Output 'deleted node state, config, the stored API credential, guest records, the feedback inbox, policy backups and logs'
}
