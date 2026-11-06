@echo off
REM Starts the PlanBOQ test emulator on THIS machine (SDK lives in C:\Users\dinat\android-sdk).
set "ANDROID_HOME=C:\Users\dinat\android-sdk"
set "JAVA_HOME=C:\Users\dinat\android-sdk\jdk\jdk-17.0.20.1+1"
set "PATH=%ANDROID_HOME%\platform-tools;%PATH%"

start "" "%ANDROID_HOME%\emulator\emulator.exe" ^
  -avd planboq ^
  -data "%ANDROID_HOME%\userdata-test.img" ^
  -no-snapshot -no-boot-anim -gpu swiftshader_indirect -memory 3072

echo Emulator starting (software GPU, ~1-2 min to boot).
echo Then run install-app.cmd to build/install/layunch the app.
