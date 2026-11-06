@echo off
REM Builds, installs and launches PlanBOQ on the running emulator.
set "ANDROID_HOME=C:\Users\dinat\android-sdk"
set "JAVA_HOME=C:\Users\dinat\android-sdk\jdk\jdk-17.0.20.1+1"
set "PATH=%ANDROID_HOME%\platform-tools;%PATH%"

cd /d "%~dp0..\android"
call gradlew.bat assembleDebug --console=plain || goto :fail
adb install -r app\build\outputs\apk\debug\app-debug.apk || goto :fail
adb shell am start -n com.planboq.app/.MainActivity
echo.
echo PlanBOQ launched. Test data: run push-testdata.cmd once.
exit /b 0

:fail
echo BUILD/INSTALL FAILED
exit /b 1
