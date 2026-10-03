# Installs the Ovenlight connector and runs it in the background as the scheduled task
# "ovenlight": from startup, as you, whether or not anyone is signed in. Its logon type is
# S4U, so Windows stores no password. From a release, it installs the ovenlight.exe next
# to this script; in the source tree, it builds one.
# Safe to run again: it rebuilds, reinstalls and restarts. Node state and the app list
# are kept.
#
# Run it from an administrator PowerShell, signed in as the account that will own the
# apps: only an administrator can register a task that runs with nobody signed in.
#   powershell -ExecutionPolicy Bypass -File .\install.ps1
$ErrorActionPreference = 'Stop'

function Fail($message) {
  [Console]::Error.WriteLine($message)
  exit 1
}

$task = 'ovenlight'
$support = Join-Path $env:LOCALAPPDATA 'ovenlight'
$binDir = Join-Path $support 'bin'
$bin = Join-Path $binDir 'ovenlight.exe'
$config = Join-Path $support 'config.json'
$log = Join-Path $support 'ovenlight.log'
# The connector itself; other ovenlight processes, such as an MCP server, aren't it.
function Running {
  Get-CimInstance Win32_Process -Filter "Name = 'ovenlight.exe'" | Where-Object { $_.ExecutablePath -eq $bin -and $_.CommandLine -like '* run *' }
}

$me = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not ([Security.Principal.WindowsPrincipal]$me).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  Fail 'install.ps1 needs an administrator PowerShell: right-click PowerShell, choose Run as administrator, and run it again.'
}
# One computer runs one connector: never take over another account's.
$existing = Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
if ($existing -and (New-Object Security.Principal.NTAccount $existing.Principal.UserId).Translate([Security.Principal.SecurityIdentifier]) -ne $me.User) {
  Fail "the connector here runs as $($existing.Principal.UserId): uninstall it from that account first"
}

# A release ships the binary next to this script; the source tree has go.mod instead.
$prebuilt = Join-Path $PSScriptRoot 'ovenlight.exe'
# go itself enforces go.mod's version, fetching a newer toolchain when it needs one.
if (-not (Test-Path $prebuilt) -and -not (Get-Command go -ErrorAction SilentlyContinue)) {
  Fail 'install.ps1 needs Go to build the connector: winget install GoLang.Go, or https://go.dev/dl/'
}

# %LOCALAPPDATA% is yours alone (and SYSTEM's and the Administrators'), and so is
# everything the connector keeps in it.
New-Item -ItemType Directory -Force $binDir | Out-Null

$new = Join-Path $binDir 'ovenlight.new.exe'
if (Test-Path $prebuilt) {
  Write-Output "installing $bin"
  Copy-Item $prebuilt $new -Force
  Unblock-File $new # a downloaded release is marked as from the internet
} else {
  Write-Output "building $bin"
  Push-Location $PSScriptRoot
  try { go build -trimpath -o $new . } finally { Pop-Location }
  if ($LASTEXITCODE) { Fail 'go build failed' }
}

# If anything below fails, the old connector, still installed, starts again.
try {
  # A running binary can't be replaced: stop the connector, disabled so a minute's try
  # doesn't start it again meanwhile (registering it again enables it).
  if ($existing) {
    Disable-ScheduledTask -TaskName $task | Out-Null
    Stop-ScheduledTask -TaskName $task
  }
  for ($i = 0; $i -lt 50 -and (Running); $i++) { Start-Sleep -Milliseconds 200 }
  # Windows won't replace or delete a running binary, such as an MCP server's, but renames
  # one: aside under a new name each time, and old ones go once nothing runs them.
  Get-ChildItem $binDir -Filter 'ovenlight.old*.exe' | Remove-Item -Force -ErrorAction SilentlyContinue
  if (Test-Path $bin) { Move-Item $bin (Join-Path $binDir "ovenlight.old-$([DateTime]::Now.Ticks).exe") }
  Move-Item $new $bin -Force

  $action = New-ScheduledTaskAction -Execute $bin -Argument ('run --log "{0}" --config "{1}" --state "{2}"' -f $log, $config, $support)
  # At startup, and every minute from now on: Task Scheduler restarts a task only when it
  # fails to start, not when it stops, so each minute it tries, and a try while it runs does
  # nothing (IgnoreNew).
  $retry = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 1)
  $triggers = @((New-ScheduledTaskTrigger -AtStartup), $retry)
  $principal = New-ScheduledTaskPrincipal -UserId $me.Name -LogonType S4U -RunLevel Limited
  # Never stopped for running long or on battery, at normal priority (a task's default is
  # below normal).
  $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -MultipleInstances IgnoreNew -Priority 5
  Register-ScheduledTask -TaskName $task -Description 'Ovenlight connector (installed by install.ps1)' `
    -Action $action -Trigger $triggers -Principal $principal -Settings $settings -Force | Out-Null
  Start-ScheduledTask -TaskName $task
} catch {
  if ($existing) {
    Enable-ScheduledTask -TaskName $task | Out-Null
    Start-ScheduledTask -TaskName $task
  }
  throw
}
# Windows can refuse to run it (Smart App Control refuses an unsigned one, such as a build
# from source), which the task itself never says.
for ($i = 0; $i -lt 50 -and -not (Running); $i++) { Start-Sleep -Milliseconds 200 }
if (-not (Running)) {
  Fail "the connector didn't start. Windows may have blocked it (Smart App Control refuses unsigned builds, such as one from source); otherwise the reason is at the end of $log"
}
Write-Output "installed the scheduled task $task (log: $log)"

# Put ovenlight on your PATH. The registry keeps the PATH as written, %variables% and all;
# Windows' own setter would expand them for good.
$envKey = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)
$path = [string]$envKey.GetValue('Path', '', 'DoNotExpandEnvironmentNames')
$cmd = 'ovenlight'
if (($path -split ';') -notcontains $binDir) {
  $envKey.SetValue('Path', ((@($path.TrimEnd(';'), $binDir) | Where-Object { $_ }) -join ';'), 'ExpandString')
  # Tell Windows the environment changed (WM_SETTINGCHANGE), so a new PowerShell gets it.
  Add-Type -Namespace Ovenlight -Name Env -MemberDefinition '[DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint msg, UIntPtr wParam, string lParam, uint flags, uint timeout, out UIntPtr result);'
  $result = [UIntPtr]::Zero
  [Ovenlight.Env]::SendMessageTimeout([IntPtr]0xffff, 0x1a, [UIntPtr]::Zero, 'Environment', 2, 5000, [ref]$result) | Out-Null
  Write-Output "added $binDir to your PATH: open a new PowerShell to run ovenlight"
  $cmd = "& '$bin'"
}
$envKey.Close()
Write-Output 'next:'
Write-Output "  $cmd publish --port <n> --name `"<App Name>`""
Write-Output "  $cmd status"
