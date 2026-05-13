@echo off
setlocal
cd /d "%~dp0.."
call gradlew.bat publishReleaseArtifacts
if %ERRORLEVEL% neq 0 (
    echo Release publish task failed.
    pause
    exit /b 1
)
echo.
echo Release artifacts are ready in:
echo   %cd%\app\release
echo.
pause
