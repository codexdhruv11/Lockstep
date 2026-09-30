@echo off
rem Runs the lockstep jar sitting beside this script, so the tool can be used as
rem `lockstep run ...` rather than `java -jar ...`.
setlocal

set "DIR=%~dp0"
set "JAR="

for %%F in ("%DIR%lockstep-*.jar") do set "JAR=%%F"
if not defined JAR if exist "%DIR%lockstep.jar" set "JAR=%DIR%lockstep.jar"
if not defined JAR if exist "%DIR%..\target\lockstep.jar" set "JAR=%DIR%..\target\lockstep.jar"

if not defined JAR (
  echo lockstep: no jar found next to %~f0 1>&2
  echo   expected lockstep.jar, lockstep-^<version^>.jar, or ..\target\lockstep.jar 1>&2
  exit /b 1
)

where java >nul 2>&1
if errorlevel 1 (
  echo lockstep: java not found on PATH. Java 21 or later is required. 1>&2
  exit /b 1
)

java %LOCKSTEP_JAVA_OPTS% -jar "%JAR%" %*
