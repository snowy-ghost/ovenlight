# Installs the Ovenlight connector on Windows. Signed in as the account that will own the
# apps, open PowerShell with Run as administrator and run:
#
#   irm https://ovenlight.app/install.ps1 | iex
#
# It takes the verified steps in docs/connector.md
# (https://ovenlight.app/docs/connector#check-a-download-first) for you. It fetches the
# index the connector updates from, latest.json, and checks its signature against the
# release keys below when this computer's ssh-keygen can. It downloads this computer's
# archive of the release the index names and checks it against the index (and, when the
# index's signature went unchecked, checks that Snowy Ghost LLC signed its programs). Then
# it unpacks it into $HOME\ovenlight-connector and runs its install.ps1, which also puts
# ovenlight on your PATH.
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
    $indexChecked = (Get-Command ssh-keygen -ErrorAction SilentlyContinue) -and
      @(cmd /d /c 'ssh-keygen -l -f keys.pub 2>nul').Count -eq $signers.Count
    if ($indexChecked) {
      cmd /d /c 'ssh-keygen -Y verify -f allowed_signers -I team@snowyghost.com -n ovenlight-latest -s latest.json.sig < latest.json'
      if ($LASTEXITCODE) { throw "the release index's signature doesn't check out, so nothing was installed. For five minutes after a release that can happen: try again, and if it keeps failing, write to team@snowyghost.com" }
    } else {
      Write-Output "The release index's signature couldn't be checked: no ssh-keygen here can read the release key that signs with Touch ID (that takes OpenSSH 8.9 or later). The download is checked against the index, and its programs against Snowy Ghost LLC's code signature, instead."
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

    New-Item -ItemType Directory release | Out-Null
    tar.exe -xf $archive -C release --strip-components 1
    if ($LASTEXITCODE) { throw "couldn't unpack $archive" }
    if (-not $indexChecked) {
      # Without the index's signature, the release's own code signature vouches for what
      # runs, before any of it reaches $dir. A link could show the check one file and the
      # copy another, so a release holding one goes no further.
      if (Get-ChildItem -Recurse -Force -Attributes ReparsePoint release) {
        throw "$archive holds links, so nothing was installed"
      }
      foreach ($file in 'ovenlight.exe', 'install.ps1', 'uninstall.ps1') {
        $signature = Get-AuthenticodeSignature (Join-Path $tmp "release\$file")
        if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.EnhancedKeyUsageList.ObjectId -notcontains $profileUsage) {
          throw "$file in $archive isn't signed by Snowy Ghost LLC, so nothing was installed"
        }
      }
      Write-Output 'checked that Snowy Ghost LLC signed ovenlight.exe, install.ps1 and uninstall.ps1'
    }
    New-Item -ItemType Directory -Force $dir | Out-Null
    Copy-Item -Recurse -Force (Join-Path $tmp 'release\*') $dir
    Write-Output "unpacked into $dir"
  } finally {
    Pop-Location
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tmp
  }
  powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $dir 'install.ps1')
  if ($LASTEXITCODE) { throw 'the install script stopped: its message is above' }
}
