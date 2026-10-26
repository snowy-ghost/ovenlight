# Installs the Ovenlight connector on Windows. Signed in as the account that will own the
# apps, open PowerShell with Run as administrator and run:
#
#   irm https://ovenlight.app/install.ps1 | iex
#
# It takes the verified steps in docs/connector.md
# (https://ovenlight.app/docs/connector#check-a-download-first) for you, all but comparing
# the index's serial with the one your connector holds. It fetches the index the connector
# updates from, latest.json, and checks its signature against the release keys below when
# this computer's ssh-keygen can. It downloads this computer's archive of the release the
# index names and checks it against the index (and, when the index's signature went
# unchecked, checks that Snowy Ghost LLC signed its programs). Then it unpacks it into
# $HOME\ovenlight-connector and runs its install.ps1, which also puts ovenlight on your
# PATH.
#
# Everything happens in one script block: a download cut short doesn't parse, so it runs
# nothing, and the block stops with an error rather than closing your PowerShell window.
& {
  $ErrorActionPreference = 'Stop'
  $base = 'https://downloads.ovenlight.app/connector'
  $dir = Join-Path $HOME 'ovenlight-connector'

  # The keys that sign releases, as docs/allowed_signers lists them (a test checks).
  $signers = @(
    'team@snowyghost.com namespaces="file,ovenlight-latest" sk-ecdsa-sha2-nistp256@openssh.com AAAAInNrLWVjZHNhLXNoYTItbmlzdHAyNTZAb3BlbnNzaC5jb20AAAAIbmlzdHAyNTYAAABBBNGElEfuj/FR/VGf3W0dW/8AZBqOHB+dY1qIa91ydBA/yMgdbWvBZ6GE1FTXrbGRzyp9AQygHn2lkSDzQbfAleEAAAAEc3NoOg=='
    'team@snowyghost.com namespaces="file,ovenlight-latest" ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEDOy2WEnSb8Zrd39QCghqqIg37IYk9ayCjxB15ukqD4'
  )
  # The usage every certificate from Snowy Ghost LLC's Artifact Signing profile carries,
  # which names that profile and no one else's (scripts/release-connector.sh).
  $profileUsage = '1.3.6.1.4.1.311.97.6252835.228622275.171996091.878164712'

  $me = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
  if (-not $me.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'install.ps1 needs an administrator PowerShell: right-click PowerShell, choose Run as administrator, and run it again.'
  }
  # A 32-bit PowerShell can't see Windows' own ssh-keygen.
  if ([Environment]::Is64BitOperatingSystem -and -not [Environment]::Is64BitProcess) {
    throw 'install.ps1 needs the 64-bit PowerShell: open Windows PowerShell, not Windows PowerShell (x86), with Run as administrator, and run it again.'
  }
  # The computer's own processor, even in a PowerShell that runs emulated.
  $cpu = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment').PROCESSOR_ARCHITECTURE
  $system = @{ AMD64 = 'windows-amd64'; ARM64 = 'windows-arm64' }[$cpu]
  if (-not $system) { throw "there's no release for $cpu" }
  $archive = "ovenlight-connector-$system.zip"

  $tmp = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
  New-Item -ItemType Directory $tmp | Out-Null
  Push-Location $tmp
  try {
    curl.exe -fsS --fail-early --proto =https -o latest.json "$base/latest.json" -o latest.json.sig "$base/latest.json.sig"
    if ($LASTEXITCODE) { throw "couldn't download the release index" }

    # With Unix line ends, as ssh-keygen reads them.
    [IO.File]::WriteAllText("$tmp\allowed_signers", ($signers -join "`n") + "`n")
    [IO.File]::WriteAllText("$tmp\keys.pub", (($signers | ForEach-Object { ($_ -split ' ', 3)[2] }) -join "`n") + "`n")
    # Windows' own OpenSSH before 8.9 can't read the key that signs with Touch ID, and
    # leaves it out here. cmd runs ssh-keygen, since PowerShell 5.1 can't redirect a file to
    # a program's input, and /d keeps it from running the user's AutoRun commands first. No
    # program here writes to its error output unless it fails: a PowerShell without a
    # console, such as the ISE, turns that into an error that stops this.
    $keygen = Get-Command ssh-keygen -ErrorAction SilentlyContinue
    $indexChecked = $keygen -and @(cmd /d /c 'ssh-keygen -l -f keys.pub 2>nul').Count -eq $signers.Count
    if ($indexChecked) {
      cmd /d /c 'ssh-keygen -Y verify -f allowed_signers -I team@snowyghost.com -n ovenlight-latest -s latest.json.sig < latest.json'
      if ($LASTEXITCODE) { throw "the release index's signature doesn't check out, so nothing was installed. For five minutes after a release that can happen: try again, and if it keeps failing, write to team@snowyghost.com" }
    } elseif (-not $keygen) {
      Write-Output "This computer has no ssh-keygen to check the release index's signature with (Windows' OpenSSH Client, an optional feature, adds one). The download is checked against the index, and its programs against Snowy Ghost LLC's code signature, instead."
    } else {
      Write-Output "This computer can't check the release index's signature: its ssh-keygen can't read the release key (that takes OpenSSH 8.9 or later). The download is checked against the index, and its programs against Snowy Ghost LLC's code signature, instead."
    }

    $index = Get-Content -Raw latest.json | ConvertFrom-Json
    $version = $index.latest
    if ($version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { throw 'the release index names no release' }
    Write-Output "downloading the Ovenlight connector $version for $system"
    curl.exe -fsSL --proto =https -o $archive "$base/$version/$archive"
    if ($LASTEXITCODE) { throw "couldn't download $archive" }
    if ((Get-FileHash $archive).Hash -ne $index.archives.$archive) {
      throw "$archive doesn't match the release index, so nothing was installed"
    }
    Write-Output "checked $archive against the release index"

    # .NET's own zip reader reads nothing but a zip, makes no links, and keeps every file
    # inside the folder it unpacks into.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $tmp $archive), (Join-Path $tmp 'release'))
    $release = Join-Path $tmp "release\ovenlight-connector-$version"
    if (-not $indexChecked) {
      # Without the index's signature, the release's own code signature vouches for what
      # runs, before any of it reaches $dir.
      $certificates = foreach ($file in 'ovenlight.exe', 'install.ps1', 'uninstall.ps1') {
        $signature = Get-AuthenticodeSignature (Join-Path $release $file)
        if ($signature.Status -ne 'Valid') {
          throw "$file in $archive has no valid code signature ($($signature.StatusMessage)), so nothing was installed"
        }
        if ($signature.SignerCertificate.EnhancedKeyUsageList.ObjectId -notcontains $profileUsage) {
          throw "$file in $archive isn't signed by Snowy Ghost LLC, so nothing was installed"
        }
        $signature.SignerCertificate.Thumbprint
      }
      # One jsign run signs all three under one certificate. No release before 1.1.0, whose
      # install.ps1 sets up a connector that never updates itself, shares a certificate
      # with a later one, so its script can't ride along with a newer binary.
      if (@($certificates | Select-Object -Unique).Count -ne 1) {
        throw "$archive mixes files from different signings, so nothing was installed"
      }
      # A signature doesn't name its file, so either script could stand in for the other:
      # each is known by its first line.
      $firstLines = @{
        'install.ps1' = '# Installs the Ovenlight connector *'
        'uninstall.ps1' = '# Stops and removes the ovenlight scheduled task*'
      }
      foreach ($file in $firstLines.Keys) {
        if ((Get-Content -TotalCount 1 (Join-Path $release $file)) -notlike $firstLines[$file]) {
          throw "$file in $archive isn't the release's own $file, so nothing was installed"
        }
      }
      # Nothing unchecked rides along, such as a program a later command in that folder
      # would run (scripts/release-connector.sh packs these).
      $shipped = 'ovenlight.exe', 'install.ps1', 'uninstall.ps1', 'LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.txt'
      if (Get-ChildItem -Force $release | Where-Object { $_.PSIsContainer -or $_.Name -notin $shipped }) {
        throw "$archive holds files a release doesn't, so nothing was installed"
      }
      # A release before 1.1.0 never updates itself, so one served as the latest would stay.
      # The binary, now known to be ours, says which release it is.
      $got = (& (Join-Path $release 'ovenlight.exe') version | Out-String).Trim()
      if (-not ($got -as [version]) -or [version]$got -lt [version]'1.1.0') {
        throw "$archive holds release $got, older than any that updates itself, so nothing was installed"
      }
      Write-Output "checked that Snowy Ghost LLC signed ovenlight.exe, install.ps1 and uninstall.ps1 of release $got"
    }
    New-Item -ItemType Directory -Force $dir | Out-Null
    Copy-Item -Recurse -Force (Join-Path $release '*') $dir
    Write-Output "unpacked into $dir"
  } finally {
    Pop-Location
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tmp
  }
  powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $dir 'install.ps1')
  if ($LASTEXITCODE) { throw 'the install script stopped: its message is above' }
}
