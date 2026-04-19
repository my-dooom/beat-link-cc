#!/usr/bin/env bash
# start-server.sh – starts the beat-link gRPC server
#
# Usage:
#   ./start-server.sh [port]
#
# The server starts on port 50051 by default.
# Run `mvn package -DskipTests` inside beat-link-grpc/ first to build the fat JAR.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${SCRIPT_DIR}/beat-link-grpc/target/beat-link-grpc-server.jar"
PORT="${1:-50051}"

if [[ ! -f "$JAR" ]]; then
    echo "ERROR: fat JAR not found at $JAR"
    echo "Build it first:"
    echo "  cd beat-link-grpc && mvn package -DskipTests"
    exit 1
fi

echo "Starting beat-link gRPC server on port ${PORT}..."
exec java -jar "$JAR" "$PORT"
