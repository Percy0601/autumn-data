@echo off
rem ===========================================================================
rem  H2 Database Server -- stop
rem
rem  Stops the TCP server started by start-h2.bat:
rem    1) graceful shutdown via "org.h2.tools.Server -tcpShutdown" (needs the
rem       same -tcpPassword the server was started with, see h2.tcp-password)
rem    2) fallback: kill the java process running org.h2.tools.Server
rem
rem  Environment: same as start-h2.bat (H2_HOME / H2_PORT / H2_TCP_PASSWORD).
rem ===========================================================================
setlocal EnableExtensions

set "PS_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS_EXE%" set "PS_EXE=powershell.exe"

rem ---- locate H2_HOME -------------------------------------------------------
if not defined H2_HOME (
    dir /b "%~dp0bin\h2-*.jar" >nul 2>&1
    if not errorlevel 1 set "H2_HOME=%~dp0"
)
if defined H2_HOME if "%H2_HOME:~-1%"=="\" set "H2_HOME=%H2_HOME:~0,-1%"
if not defined H2_HOME set "H2_HOME=D:\opt\h2"

if not defined H2_PORT set "H2_PORT=9092"
if not defined H2_TCP_PASSWORD if exist "%H2_HOME%\h2.tcp-password" set /p H2_TCP_PASSWORD=<"%H2_HOME%\h2.tcp-password"

set "H2_JAR="
for /f "delims=" %%f in ('dir /b /o-n "%H2_HOME%\bin\h2-*.jar" 2^>nul') do if not defined H2_JAR set "H2_JAR=%H2_HOME%\bin\%%f"
if not defined H2_JAR goto :no_jar

set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for /f "delims=" %%j in ('where java.exe 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%j"
if not defined JAVA_EXE goto :no_java

rem ---- nothing listening? still clean up stale processes --------------------
call :port_check
if errorlevel 1 goto :check_leftover

rem ---- 1) graceful shutdown ------------------------------------------------
echo [h2] shutting down TCP server on port %H2_PORT% ...
if defined H2_TCP_PASSWORD (
    "%JAVA_EXE%" -cp "%H2_JAR%" org.h2.tools.Server -tcpPassword "%H2_TCP_PASSWORD%" -tcpShutdown "tcp://localhost:%H2_PORT%" -tcpShutdownForce
) else (
    "%JAVA_EXE%" -cp "%H2_JAR%" org.h2.tools.Server -tcpShutdown "tcp://localhost:%H2_PORT%" -tcpShutdownForce
)

set /a H2_WAIT=0
:wait_loop
call :port_check
if errorlevel 1 goto :stopped
set /a H2_WAIT+=1
if %H2_WAIT% GEQ 10 goto :kill_leftover
"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "Start-Sleep -Milliseconds 900" >nul 2>&1
goto :wait_loop

rem ---- 2) fallback: kill the java process ----------------------------------
:kill_leftover
echo [h2] graceful shutdown failed - killing the server process ...
"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "$p = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*org.h2.tools.Server*' -and $_.CommandLine -like '*h2-*' }; foreach ($x in $p) { Stop-Process -Id $x.ProcessId -Force -ErrorAction SilentlyContinue; Write-Output ('[h2] killed pid ' + $x.ProcessId) }" >nul 2>&1
"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "Start-Sleep -Milliseconds 900" >nul 2>&1
call :port_check
if not errorlevel 1 goto :stop_failed
echo [h2] stopped (port %H2_PORT% released)
exit /b 0

rem ---- server not listening: clean up stale processes anyway ----------------
:check_leftover
"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "$p = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*org.h2.tools.Server*' -and $_.CommandLine -like '*h2-*' }; if ($p) { foreach ($x in $p) { Stop-Process -Id $x.ProcessId -Force -ErrorAction SilentlyContinue; Write-Output ('[h2] killed stale pid ' + $x.ProcessId) } } else { exit 3 }" >nul 2>&1
if errorlevel 3 echo [h2] not running - nothing to stop
if not errorlevel 3 echo [h2] stale server process cleaned up
exit /b 0

:port_check
"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', %H2_PORT%); $c.Close(); exit 0 } catch { exit 1 }" >nul 2>&1
exit /b %ERRORLEVEL%

:stopped
echo [h2] stopped (port %H2_PORT% released)
exit /b 0

:stop_failed
echo [h2] [ERROR] port %H2_PORT% is still in use by another process
echo [h2] find the owner with: netstat -ano ^| findstr :%H2_PORT%
exit /b 1

:no_jar
echo [h2] [ERROR] no h2-*.jar under "%H2_HOME%\bin"
exit /b 1

:no_java
echo [h2] [ERROR] java.exe not found - set JAVA_HOME to a JDK 11+ install
exit /b 1
