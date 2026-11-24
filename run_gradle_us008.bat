@echo off
cd /d D:\aura\wt\release21
set HOME=C:\Users\magic_new.BETOS-AIO.000
set ANDROID_HOME=
call gradlew.bat :ai:testDebugUnitTest --offline > C:\qwenpaw-testbed-8099\logs\gradle_us008.txt 2>&1
echo GRADLE_RC=%ERRORLEVEL% >> C:\qwenpaw-testbed-8099\logs\gradle_us008.txt
