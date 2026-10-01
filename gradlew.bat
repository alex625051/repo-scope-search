@echo off
setlocal EnableExtensions DisableDelayedExpansion

rem ---------------------------------------------------------------------------
rem Repo Scope Search Gradle launcher for Windows
rem Java lookup order:
rem   1. gradleJavaHome from project gradle.properties
rem   2. <pycharmLocalPath>\jbr from project gradle.properties
rem   3. JAVA_HOME
rem   4. java.exe from PATH
rem ---------------------------------------------------------------------------

set "APP_HOME=%~dp0"
for %%I in ("%APP_HOME%.") do set "APP_HOME=%%~fI"
set "APP_BASE_NAME=gradlew"
set "WRAPPER_JAR=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar"
set "PROPERTIES_FILE=%APP_HOME%\gradle.properties"
set "GRADLE_JAVA_HOME="
set "PYCHARM_LOCAL_PATH="
set "JAVA_EXE="
set "JAVA_SOURCE="

if exist "%PROPERTIES_FILE%" (
    for /f "usebackq tokens=1,* delims==" %%A in ("%PROPERTIES_FILE%") do (
        if /i "%%A"=="gradleJavaHome" set "GRADLE_JAVA_HOME=%%B"
        if /i "%%A"=="pycharmLocalPath" set "PYCHARM_LOCAL_PATH=%%B"
    )
)

rem Remove optional quotes and convert forward slashes to Windows separators.
if defined GRADLE_JAVA_HOME set "GRADLE_JAVA_HOME=%GRADLE_JAVA_HOME:"=%"
if defined GRADLE_JAVA_HOME set "GRADLE_JAVA_HOME=%GRADLE_JAVA_HOME:/=\%"
if defined PYCHARM_LOCAL_PATH set "PYCHARM_LOCAL_PATH=%PYCHARM_LOCAL_PATH:"=%"
if defined PYCHARM_LOCAL_PATH set "PYCHARM_LOCAL_PATH=%PYCHARM_LOCAL_PATH:/=\%"

if defined GRADLE_JAVA_HOME if exist "%GRADLE_JAVA_HOME%\bin\java.exe" (
    set "JAVA_EXE=%GRADLE_JAVA_HOME%\bin\java.exe"
    set "JAVA_SOURCE=gradleJavaHome"
)

if not defined JAVA_EXE if defined PYCHARM_LOCAL_PATH if exist "%PYCHARM_LOCAL_PATH%\jbr\bin\java.exe" (
    set "JAVA_EXE=%PYCHARM_LOCAL_PATH%\jbr\bin\java.exe"
    set "JAVA_SOURCE=pycharmLocalPath\jbr"
)

if not defined JAVA_EXE if defined JAVA_HOME call :tryJavaHome "%JAVA_HOME%" "JAVA_HOME"

if not defined JAVA_EXE (
    for /f "delims=" %%J in ('where java.exe 2^>nul') do if not defined JAVA_EXE (
        set "JAVA_EXE=%%J"
        set "JAVA_SOURCE=PATH"
    )
)

if not defined JAVA_EXE goto javaNotFound
if not exist "%WRAPPER_JAR%" goto wrapperNotFound

if not defined REPO_SCOPE_QUIET (
    echo [Repo Scope Search] Java: "%JAVA_EXE%"
    echo [Repo Scope Search] Source: %JAVA_SOURCE%
    if defined PYCHARM_LOCAL_PATH echo [Repo Scope Search] PyCharm: "%PYCHARM_LOCAL_PATH%"
)

"%JAVA_EXE%" %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
set "EXIT_CODE=%ERRORLEVEL%"
goto end

:tryJavaHome
set "CANDIDATE_JAVA_HOME=%~1"
if exist "%CANDIDATE_JAVA_HOME%\bin\java.exe" (
    set "JAVA_EXE=%CANDIDATE_JAVA_HOME%\bin\java.exe"
    set "JAVA_SOURCE=%~2"
)
exit /b 0

:javaNotFound
>&2 echo.
>&2 echo ERROR: Java was not found.
>&2 echo.
>&2 echo Checked:
>&2 echo   1. gradleJavaHome in "%PROPERTIES_FILE%"
>&2 echo   2. pycharmLocalPath\jbr in "%PROPERTIES_FILE%"
>&2 echo   3. JAVA_HOME
>&2 echo   4. java.exe in PATH
>&2 echo.
if defined PYCHARM_LOCAL_PATH >&2 echo Configured pycharmLocalPath: "%PYCHARM_LOCAL_PATH%"
>&2 echo pycharmLocalPath must point to the PyCharm installation directory containing jbr\bin\java.exe.
>&2 echo Example: pycharmLocalPath=C:/Users/adminadmin/AppData/Local/Programs/PyCharm 2025.2.6.2
set "EXIT_CODE=1"
goto end

:wrapperNotFound
>&2 echo ERROR: Gradle Wrapper JAR was not found:
>&2 echo "%WRAPPER_JAR%"
>&2 echo Restore gradle\wrapper\gradle-wrapper.jar.
set "EXIT_CODE=1"

:end
endlocal & exit /b %EXIT_CODE%
