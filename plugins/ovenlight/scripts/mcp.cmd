@echo off
rem Starts the Ovenlight connector's MCP server from where install.ps1 puts the connector.
rem Claude Code runs this on Windows, in place of the mcp script beside it.
set "bin=%LOCALAPPDATA%\ovenlight\bin\ovenlight.exe"
if not exist "%bin%" (
  >&2 echo The Ovenlight connector isn't installed: there's no %%LOCALAPPDATA%%\ovenlight\bin\ovenlight.exe. Install it with the steps at https://ovenlight.app/support#own-computer, then reconnect the ovenlight server in /mcp.
  exit /b 1
)
"%bin%" mcp %*
