@echo off
rem Module-relative Gradle wrapper delegate.
rem
rem The Android lane's wrapper lives at the PROJECT ROOT (android\gradlew.bat,
rem with android\gradle\wrapper\); tooling that resolves the entry point
rem module-relatively (android\app\gradlew.bat) lands here. Delegate to the
rem root wrapper with the working directory pinned to the project root: the
rem wrapper resolves its own APP_HOME from its script location, and Gradle
rem resolves the project (android\settings.gradle.kts) from the working
rem directory — so the effective project and its configuration (locks,
rem fences, -PpocketAbi/-PpocketLane gates) are identical to invoking the
rem root wrapper directly, from any caller working directory, and a
rem caller-supplied -p still resolves (against the project root, whose
rem nearest settings file is the same one).
cd /d "%~dp0.."
call gradlew.bat %*
exit /b %ERRORLEVEL%
