@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables, and ensure extensions are enabled
setlocal EnableExtensions

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

@rem JAVA_HOME unset and no java on PATH — probe well-known install locations.
@rem Order: IntelliJ-managed .jdks first (project-correct JBR for this IDEA plugin),
@rem then Gradle toolchain cache, then bundled JBRs, then system JDK installs.
call :findJdkChild "%USERPROFILE%\.jdks"
if defined JAVA_HOME goto findJavaFromJavaHome
call :findJdkChild "%USERPROFILE%\.gradle\jdks"
if defined JAVA_HOME goto findJavaFromJavaHome
call :findJbrChild "%ProgramFiles%\JetBrains"
if defined JAVA_HOME goto findJavaFromJavaHome
call :findJdkChild "%ProgramFiles%\Eclipse Adoptium"
if defined JAVA_HOME goto findJavaFromJavaHome
call :findJdkChild "%ProgramFiles%\Java"
if defined JAVA_HOME goto findJavaFromJavaHome
call :findJdkChild "%ProgramFiles%\Microsoft"
if defined JAVA_HOME goto findJavaFromJavaHome
call :findJdkChild "%ProgramFiles%\BellSoft"
if defined JAVA_HOME goto findJavaFromJavaHome
call :findJdkChild "%ProgramFiles%\Amazon Corretto"
if defined JAVA_HOME goto findJavaFromJavaHome
call :findJdkChild "%ProgramFiles%\Zulu"
if defined JAVA_HOME goto findJavaFromJavaHome

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

exit /b 1

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

exit /b 1

@rem Pick the lexically-newest child of %~1 that has bin\java.exe; set JAVA_HOME to it.
:findJdkChild
if not exist "%~1" exit /b 0
for /f "delims=" %%j in ('dir /b /ad /o-n "%~1" 2^>nul') do (
  if exist "%~1\%%j\bin\java.exe" (
    set "JAVA_HOME=%~1\%%j"
    exit /b 0
  )
)
exit /b 0

@rem JetBrains IDE installs nest java at <IDE>\jbr\bin\java.exe; pick the lexically-newest IDE.
:findJbrChild
if not exist "%~1" exit /b 0
for /f "delims=" %%i in ('dir /b /ad /o-n "%~1" 2^>nul') do (
  if exist "%~1\%%i\jbr\bin\java.exe" (
    set "JAVA_HOME=%~1\%%i\jbr"
    exit /b 0
  )
)
exit /b 0

:execute
@rem Setup the command line



@rem Execute Gradle
@rem endlocal doesn't take effect until after the line is parsed and variables are expanded
@rem which allows us to clear the local environment before executing the java command
endlocal & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %* & call :exitWithErrorLevel

:exitWithErrorLevel
@rem Use "%COMSPEC%" /c exit to allow operators to work properly in scripts
"%COMSPEC%" /c exit %ERRORLEVEL%
