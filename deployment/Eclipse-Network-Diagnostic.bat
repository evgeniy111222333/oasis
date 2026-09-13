@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul

set "EXPECTED_SIZE=904138"
set "EXPECTED_SHA1=580547402240EFC23315A2A7DDE5C03ACBEC0E80"
set "OUT=%~dp0Eclipse-Network-Diagnostic-Results"
set "LOG=%OUT%\diagnostic-report.txt"

if not exist "%OUT%" mkdir "%OUT%" >nul 2>&1
if not exist "%OUT%" (
    echo ERROR: Could not create the results directory next to this BAT file.
    echo Move the BAT file to Downloads or Desktop and run it again.
    if not defined ECLIPSE_DIAG_NO_PAUSE pause
    exit /b 1
)

where curl.exe >nul 2>&1
if errorlevel 1 (
    echo ERROR: curl.exe is not available on this Windows installation.
    echo Please send a screenshot of this window.
    if not defined ECLIPSE_DIAG_NO_PAUSE pause
    exit /b 2
)

>"%LOG%" echo Eclipse Network Diagnostic
>>"%LOG%" echo Started: %DATE% %TIME%
>>"%LOG%" echo Windows: 
>>"%LOG%" ver
>>"%LOG%" echo Expected size: %EXPECTED_SIZE% bytes
>>"%LOG%" echo Expected SHA1: %EXPECTED_SHA1%
>>"%LOG%" echo.

echo Eclipse Network Diagnostic
echo Results will be saved to:
echo %LOG%
echo.
echo Each test can take up to 90 seconds. Please do not close this window.
echo.

call :TEST r2 "https://dist.eclipse-roleplay.online/client/mods/eclipse-client-1.2.6.jar"
call :TEST vps "https://api.eclipse-roleplay.online/dist/client/mods/eclipse-client-1.2.6.jar"
call :TEST github "https://raw.githubusercontent.com/evgeniy111222333/oasis/dist/client/mods/eclipse-client-1.2.6.jar"
call :TEST r2dev "https://pub-b766c8d6775740beb9a1d74a4b7b6067.r2.dev/client/mods/eclipse-client-1.2.6.jar"

>>"%LOG%" echo Finished: %DATE% %TIME%
echo.
echo Diagnostic finished.
echo Send this file back:
echo %LOG%

if not defined ECLIPSE_DIAG_NO_PAUSE (
    start "" notepad.exe "%LOG%"
    pause
)
exit /b 0

:TEST
set "NAME=%~1"
set "URL=%~2"
set "FILE=%OUT%\%NAME%.jar"
set "CURL_LOG=%OUT%\%NAME%-curl.txt"
set "SIZE=missing"
set "HASH=missing"
set "SIZE_STATUS=FAIL"
set "HASH_STATUS=FAIL"

if exist "%FILE%" del /q "%FILE%" >nul 2>&1
if exist "%CURL_LOG%" del /q "%CURL_LOG%" >nul 2>&1

echo Testing %NAME%...
curl.exe --http1.1 -L --connect-timeout 10 --max-time 90 --retry 0 --output "%FILE%" "%URL%" >"%CURL_LOG%" 2>&1
set "CURL_EXIT=!ERRORLEVEL!"

if exist "%FILE%" (
    for %%F in ("%FILE%") do set "SIZE=%%~zF"
    if "!SIZE!"=="%EXPECTED_SIZE%" set "SIZE_STATUS=OK"

    for /f "usebackq delims=" %%H in (`certutil -hashfile "%FILE%" SHA1 2^>nul ^| findstr /R /I /C:"^[0-9A-F][0-9A-F]*$"`) do set "HASH=%%H"
    set "HASH=!HASH: =!"
    if /I "!HASH!"=="%EXPECTED_SHA1%" set "HASH_STATUS=OK"
)

echo   curl_exit=!CURL_EXIT! bytes=!SIZE! size=!SIZE_STATUS! sha1=!HASH_STATUS!
>>"%LOG%" echo [!NAME!]
>>"%LOG%" echo URL=!URL!
>>"%LOG%" echo curl_exit=!CURL_EXIT!
>>"%LOG%" echo bytes=!SIZE!
>>"%LOG%" echo size_status=!SIZE_STATUS!
>>"%LOG%" echo sha1=!HASH!
>>"%LOG%" echo sha1_status=!HASH_STATUS!
>>"%LOG%" echo curl_output:
>>"%LOG%" type "%CURL_LOG%"
>>"%LOG%" echo.
exit /b 0
