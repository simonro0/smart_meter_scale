@echo off
echo Building debug APK...
call gradlew.bat assembleDebug
if errorlevel 1 exit /b 1

for %%f in (app\build\outputs\apk\debug\*.apk) do (
    copy /y "%%f" "%USERPROFILE%\Desktop\%%~nxf"
    echo APK copied to: %USERPROFILE%\Desktop\%%~nxf
)
