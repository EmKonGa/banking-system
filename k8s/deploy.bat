@echo off
REM Runs deploy.ps1 from cmd.exe / Cmder, where .ps1 files do not execute directly.
REM
REM     k8s\deploy.bat
REM     k8s\deploy.bat -SkipBuild
REM     k8s\deploy.bat -Recreate
REM
REM This is a wrapper, not a port. The deploy logic stays in deploy.ps1 because it needs arrays,
REM string matching and exit-code handling that batch does either badly or not at all -- and one
REM implementation cannot drift from the other.
REM
REM %~dp0 is this file's own directory, so the script is found no matter where you run it from.
REM -ExecutionPolicy Bypass avoids the unsigned-script block without changing any machine setting;
REM it applies only to this one invocation.
REM %* forwards every argument through unchanged.

setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*
REM Propagate the real exit code so CI, or `if errorlevel 1`, still sees a failure.
exit /b %ERRORLEVEL%
