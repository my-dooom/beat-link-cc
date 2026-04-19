@echo off
REM start-server.bat – starts the beat-link gRPC server on Windows
REM
REM Usage:
REM   start-server.bat [port]
REM
REM The server starts on port 50051 by default.
REM Run `mvn package -DskipTests` inside beat-link-grpc\ first to build the fat JAR.

setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "JAR=%SCRIPT_DIR%beat-link-grpc\target\beat-link-grpc-server.jar"
set "PORT=%~1"
if "!PORT!"=="" set "PORT=50051"

if not exist "!JAR!" (
    echo ERROR: fat JAR not found at !JAR!
    echo Build it first:
    echo   cd beat-link-grpc ^&^& mvn package -DskipTests
    exit /b 1
)

echo Starting beat-link gRPC server on port !PORT!...
java -jar "!JAR!" "!PORT!"
