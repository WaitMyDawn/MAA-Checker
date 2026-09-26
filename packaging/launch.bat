@echo off
rem ============================================================================
rem  MAA-Checker launcher
rem  Kept ASCII-only on purpose: a .bat is read by cmd.exe using the system code
rem  page, so non-ASCII text would turn into mojibake on some machines.
rem  Chinese guidance lives in README.txt and in the Java GUI itself.
rem
rem  1) use bundled jre\ if present (JRE-included build)
rem  2) otherwise find a Java 21+ (HMCL runtime / JAVA_HOME / PATH / common paths)
rem  3) if none found, print clear instructions instead of failing silently
rem ============================================================================
setlocal
cd /d "%~dp0"
chcp 65001 >nul

set "JAVA_EXE="
if exist "jre\bin\java.exe" set "JAVA_EXE=%CD%\jre\bin\java.exe"

if not defined JAVA_EXE (
    for /f "usebackq delims=" %%j in (`powershell -NoProfile -ExecutionPolicy Bypass -File "tools\find-java.ps1"`) do set "JAVA_EXE=%%j"
)

if not defined JAVA_EXE (
    echo.
    echo  ================================================================
    echo   Java 21 or newer was not found. MAA-Checker needs it to run.
    echo.
    echo   What to do ^(any one of these^):
    echo     1. Install Java 21 ^(Temurin 21 or Microsoft OpenJDK 21^)
    echo     2. Have HMCL installed? Its bundled runtime is auto-detected at
    echo        %%APPDATA%%\.hmcl\java\windows-x86_64
    echo     3. Set JAVA_HOME to your JDK 21 folder, then reopen this window
    echo.
    echo   Self-check: run  java -version  in a command prompt
    echo  ================================================================
    echo.
    echo   ^(Chinese notes: see README.txt in this folder^)
    echo.
    pause
    exit /b 2
)

echo Using Java: %JAVA_EXE%
start "" "%JAVA_EXE%" -Xmx512m -Dstdout.encoding=UTF-8 -Dmaachecker.home="%CD%" -jar "maa-checker.jar" --gui
exit /b 0
