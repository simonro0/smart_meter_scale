@echo off
echo Building release APK...
call gradlew.bat assembleRelease
if errorlevel 1 exit /b 1

for %%f in (app\build\outputs\apk\release\*.apk) do (
    copy /y "%%f" "%USERPROFILE%\Desktop\%%~nxf"
    echo APK copied to: %USERPROFILE%\Desktop\%%~nxf
)
