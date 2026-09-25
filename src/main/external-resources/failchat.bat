@echo off
setlocal

cd /d "%~dp0"

if not exist "%~dp0runtime\bin\javaw.exe" (
    echo Failchat runtime was not found:
    echo "%~dp0runtime\bin\javaw.exe"
    echo.
    echo Make sure you extracted the complete Failchat package.
    pause
    exit /b 1
)

if not exist "%~dp0java-agents\transparent-webview-patch.jar" (
    echo Failchat WebView patch was not found:
    echo "%~dp0java-agents\transparent-webview-patch.jar"
    echo.
    echo Make sure you extracted the complete Failchat package.
    pause
    exit /b 1
)

"%~dp0runtime\bin\javaw.exe" -Xmx200m -Xms100m -XX:+UseG1GC -javaagent:"%~dp0java-agents\transparent-webview-patch.jar" -jar "%~dp0failchat-${project.version}.jar"

exit /b %ERRORLEVEL%
