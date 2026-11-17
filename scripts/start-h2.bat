@echo off
rem ===========================================================================
rem  H2 Database Server -- start (TCP + MySQL compatibility mode)
rem
rem  Usage:
rem    start-h2.bat                 start the TCP server in a minimized window
rem    start-h2.bat --foreground    run in this console (server log visible)
rem    start-h2.bat --status        report whether the server is listening
rem    start-h2.bat --help
rem
rem  Environment (all optional):
rem    H2_HOME           install dir; default: this script's dir when it holds
rem                      bin\h2-*.jar, otherwise D:\opt\h2
rem    H2_PORT           TCP port                          (default 9092)
rem    H2_BASE_DIR       database file directory           (default %H2_HOME%\data)
rem    H2_ALLOW_OTHERS   1 = accept connections from other hosts (default 0)
rem    H2_PASSWORD       sa password used when a database is created (default empty)
rem    H2_TCP_PASSWORD   password for "-tcpShutdown" (used by stop-h2.bat);
rem                      default: generated once and kept in %H2_HOME%\h2.tcp-password
rem    H2_DATABASES      databases prepared in MySQL mode, comma/space separated
rem                      (default autumn_data)
rem    JAVA_HOME         JDK 11+; falls back to java.exe on PATH
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

rem ---- defaults -------------------------------------------------------------
if not defined H2_PORT set "H2_PORT=9092"
if not defined H2_BASE_DIR set "H2_BASE_DIR=%H2_HOME%\data"
if not defined H2_ALLOW_OTHERS set "H2_ALLOW_OTHERS=0"
if not defined H2_DATABASES set "H2_DATABASES=autumn_data"
rem commas become spaces so "for" can iterate the list
set "H2_DATABASES=%H2_DATABASES:,= %"

rem ---- shutdown password: env -> file -> generate once ---------------------
if not defined H2_TCP_PASSWORD if exist "%H2_HOME%\h2.tcp-password" set /p H2_TCP_PASSWORD=<"%H2_HOME%\h2.tcp-password"
if defined H2_TCP_PASSWORD goto :tcp_pwd_ready
set "H2_TCP_PASSWORD=%RANDOM%%RANDOM%%RANDOM%%RANDOM%%RANDOM%"
>"%H2_HOME%\h2.tcp-password" echo %H2_TCP_PASSWORD%
:tcp_pwd_ready

if "%H2_ALLOW_OTHERS%"=="1" (set "H2_EXTRA=-tcpAllowOthers") else (set "H2_EXTRA=")
set "H2_AUTH=-tcpPassword "%H2_TCP_PASSWORD%""

rem ---- locate h2 jar (highest version in bin\) ------------------------------
set "H2_JAR="
for /f "delims=" %%f in ('dir /b /o-n "%H2_HOME%\bin\h2-*.jar" 2^>nul') do if not defined H2_JAR set "H2_JAR=%H2_HOME%\bin\%%f"
if not defined H2_JAR goto :no_jar

rem ---- locate java (11+ required by H2 2.x) ---------------------------------
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for /f "delims=" %%j in ('where java.exe 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%j"
if not defined JAVA_EXE goto :no_java

"%JAVA_EXE%" -cp "%H2_JAR%" org.h2.tools.Server -help >nul 2>&1
if errorlevel 1 goto :bad_java

rem ---- dispatch -------------------------------------------------------------
if /i "%~1"=="--help" goto :usage
if /i "%~1"=="-h" goto :usage
if /i "%~1"=="--status" goto :status
if /i "%~1"=="--foreground" goto :foreground

rem ---- start (background, minimized window) --------------------------------
call :port_check
if not errorlevel 1 goto :already_running

if not exist "%H2_BASE_DIR%" mkdir "%H2_BASE_DIR%" >nul 2>&1
echo [h2] h2 home   : %H2_HOME%
echo [h2] h2 jar    : %H2_JAR%
echo [h2] java      : %JAVA_EXE%
echo [h2] base dir  : %H2_BASE_DIR%
echo [h2] stop pass : %H2_HOME%\h2.tcp-password
echo [h2] starting TCP server on port %H2_PORT% ...
start "H2 Server TCP %H2_PORT%" /MIN "%JAVA_EXE%" -cp "%H2_JAR%" org.h2.tools.Server -tcp -tcpPort %H2_PORT% %H2_EXTRA% %H2_AUTH% -ifNotExists -baseDir "%H2_BASE_DIR%"

set /a H2_WAIT=0
:wait_loop
call :port_check
if not errorlevel 1 goto :ready
set /a H2_WAIT+=1
if %H2_WAIT% GEQ 30 goto :start_failed
"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "Start-Sleep -Milliseconds 900" >nul 2>&1
goto :wait_loop

:ready
echo [h2] TCP server is up on port %H2_PORT%
for %%d in (%H2_DATABASES%) do call :prepare_db "%%d"
echo.
echo [h2] ready. Connect with a MySQL-compatible URL, for example:
for %%d in (%H2_DATABASES%) do echo      jdbc:h2:tcp://localhost:%H2_PORT%/%%d;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE
echo.
echo [h2] stop with: stop-h2.bat
exit /b 0

:prepare_db
setlocal
set "DB=%~1"
set "H2_URL=jdbc:h2:tcp://localhost:%H2_PORT%/%DB%;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
"%JAVA_EXE%" -cp "%H2_JAR%" org.h2.tools.Shell -url "%H2_URL%" -user sa -password "%H2_PASSWORD%" -sql "SET MODE MySQL" >nul 2>&1
if errorlevel 1 (echo [h2] [WARN] database "%DB%" not prepared - check password or permissions) else (echo [h2] database "%DB%" prepared in MySQL mode)
endlocal & exit /b 0

:port_check
"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', %H2_PORT%); $c.Close(); exit 0 } catch { exit 1 }" >nul 2>&1
exit /b %ERRORLEVEL%

:foreground
if not exist "%H2_BASE_DIR%" mkdir "%H2_BASE_DIR%" >nul 2>&1
echo [h2] running in foreground on port %H2_PORT% - press Ctrl+C to stop
"%JAVA_EXE%" -cp "%H2_JAR%" org.h2.tools.Server -tcp -tcpPort %H2_PORT% %H2_EXTRA% %H2_AUTH% -ifNotExists -baseDir "%H2_BASE_DIR%"
exit /b %ERRORLEVEL%

:status
call :port_check
if errorlevel 1 (echo [h2] not running - port %H2_PORT% is closed) else (echo [h2] running - tcp://localhost:%H2_PORT%)
exit /b 0

:already_running
echo [h2] port %H2_PORT% is already in use - the server looks started already.
echo [h2] use stop-h2.bat to stop it, or set H2_PORT to another port.
exit /b 0

:start_failed
echo [h2] [ERROR] the server did not open port %H2_PORT% within 30s.
echo [h2] run "start-h2.bat --foreground" to see the server log.
exit /b 1

:usage
echo H2 Database Server - start script
echo.
echo   start-h2.bat                start TCP server in a minimized window
echo   start-h2.bat --foreground   run in this console (log visible)
echo   start-h2.bat --status       show whether the server is listening
echo   start-h2.bat --help         this text
echo.
echo Environment:
echo   H2_HOME=%H2_HOME%
echo   H2_PORT=%H2_PORT%
echo   H2_BASE_DIR=%H2_BASE_DIR%
echo   H2_ALLOW_OTHERS=%H2_ALLOW_OTHERS%   (1 = accept remote hosts)
echo   H2_DATABASES=%H2_DATABASES%
echo   H2_PASSWORD / H2_TCP_PASSWORD = (hidden)
exit /b 0

:no_h2
echo [h2] [ERROR] H2 install dir not found: "%H2_HOME%"
echo [h2] set H2_HOME to the directory that contains bin\h2-*.jar
exit /b 1

:no_jar
echo [h2] [ERROR] no h2-*.jar under "%H2_HOME%\bin"
echo [h2] download the latest H2 jar into this folder, e.g.
echo         https://repo1.maven.org/maven2/com/h2database/h2/
exit /b 1

:no_java
echo [h2] [ERROR] java.exe not found - set JAVA_HOME to a JDK 11+ install
exit /b 1

:bad_java
echo [h2] [ERROR] "%JAVA_EXE%" cannot run H2 2.x - JDK 11+ is required
exit /b 1
