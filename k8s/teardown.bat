@echo off
REM Runs teardown.ps1 from cmd.exe / Cmder, where .ps1 files do not execute directly.
REM
REM     k8s\teardown.bat              (delete the whole kind cluster)
REM     k8s\teardown.bat -AppOnly     (delete only the banking namespace)
REM     k8s\teardown.bat -Force       (skip the confirmation prompt)
REM
REM A wrapper, not a port -- see the note in deploy.bat. Finding kind.exe alone needs a glob over
REM the winget package directory, which batch does not do usefully.
REM
REM %~dp0 is this file's own directory, so the script is found no matter where you run it from.
REM -ExecutionPolicy Bypass avoids the unsigned-script block without changing any machine setting.
REM %* forwards every argument through unchanged.

setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0teardown.ps1" %*
REM Propagate the real exit code so `if errorlevel 1` still sees a failure.
exit /b %ERRORLEVEL%
