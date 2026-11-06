@echo off
REM Copies the generated sample plans to the emulator and indexes the images.
set "ANDROID_HOME=C:\Users\dinat\android-sdk"
set "PATH=%ANDROID_HOME%\platform-tools;%PATH%"
set "TD=%~dp0..\testdata"

adb push "%TD%\sample_plan.dxf" "%TD%\sample_plan.pdf" /sdcard/Download/
adb push "%TD%\sample_plan.png" "%TD%\sample_plan_photo.png" /sdcard/Pictures/
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/sample_plan.png
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/sample_plan_photo.png
echo.
echo Files pushed: Downloads = DXF/PDF ; Pictures = PNGs (photo picker).
