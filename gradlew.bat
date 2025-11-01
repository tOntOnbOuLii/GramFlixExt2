@ECHO OFF
SETLOCAL

set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

set "JAVA_EXE="

if defined JAVA_HOME goto findJavaFromJavaHome

for %%i in (java.exe) do set "JAVA_EXE=%%~$PATH:i"
if not defined JAVA_EXE goto javaMissing
goto execute

:findJavaFromJavaHome
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if exist "%JAVA_EXE%" goto execute
echo.
echo ERROR: JAVA_HOME is set to "%JAVA_HOME%" but "%JAVA_EXE%" was not found. >&2
echo Please check that you have a JDK installed and the JAVA_HOME environment variable points to it. >&2
exit /B 1

:javaMissing
echo.
echo ERROR: JAVA_HOME is not set and no "java.exe" executable could be found in PATH. >&2
exit /B 1

:execute
"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

ENDLOCAL
EXIT /B %ERRORLEVEL%

