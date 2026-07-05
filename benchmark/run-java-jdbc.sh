#!/bin/bash
#
# SDTP Java JDBC Integration Test — Compile & Runner
#
# Usage:
#   bash run-java-jdbc.sh compile                          # compile only
#   bash run-java-jdbc.sh tpch --host ...                  # TPC-H only
#   bash run-java-jdbc.sh tpcc --host ...                  # TPC-C only
#   bash run-java-jdbc.sh all --host ...                   # TPC-H + TPC-C
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

MYSQL_JDBC_VERSION="8.0.33"
MYSQL_JDBC_JAR="/tmp/mysql-connector-java-${MYSQL_JDBC_VERSION}.jar"
MYSQL_JDBC_URL="https://repo1.maven.org/maven2/mysql/mysql-connector-java/${MYSQL_JDBC_VERSION}/mysql-connector-java-${MYSQL_JDBC_VERSION}.jar"

JAVA_SRC="$SCRIPT_DIR/JavaJdbcTest.java"

compile() {
    echo "=== Compiling JavaJdbcTest ==="

    if [ ! -f "$MYSQL_JDBC_JAR" ]; then
        echo "Downloading mysql-connector-java:${MYSQL_JDBC_VERSION}..."
        curl -sL --retry 3 --retry-delay 2 \
            "$MYSQL_JDBC_URL" -o "$MYSQL_JDBC_JAR"
    fi

    if [ ! -f "$MYSQL_JDBC_JAR" ]; then
        echo "ERROR: Failed to download mysql-connector-java.jar"
        exit 1
    fi
    echo "MySQL JDBC driver: $MYSQL_JDBC_JAR"

    javac -cp "$MYSQL_JDBC_JAR" -d "$SCRIPT_DIR" "$JAVA_SRC"
    echo "Compiled: $SCRIPT_DIR/JavaJdbcTest.class"
}

run_benchmark() {
    local mode="$1"; shift  # remove 'tpch'/'tpcc'/'all'

    compile

    local tpch_file="$BASE_DIR/benchmark/tpch/queries_mysql.sql"
    local tpcc_file="$BASE_DIR/benchmark/tpcc/queries_mysql.sql"

    case "$mode" in
        tpch)
            echo "=== Running TPC-H via Java JDBC ==="
            java -cp "$SCRIPT_DIR:$MYSQL_JDBC_JAR" JavaJdbcTest \
                --tpch-queries "$tpch_file" --tpcc-queries "" "$@"
            ;;
        tpcc)
            echo "=== Running TPC-C via Java JDBC ==="
            java -cp "$SCRIPT_DIR:$MYSQL_JDBC_JAR" JavaJdbcTest \
                --tpcc-queries "$tpcc_file" --tpch-queries "" "$@"
            ;;
        all|run)
            echo "=== Running TPC-H + TPC-C via Java JDBC ==="
            java -cp "$SCRIPT_DIR:$MYSQL_JDBC_JAR" JavaJdbcTest \
                --tpch-queries "$tpch_file" --tpcc-queries "$tpcc_file" "$@"
            ;;
    esac
}

# ==================== Main ====================

case "${1:-help}" in
    compile)     compile ;;
    tpch|tpcc|all|run)  run_benchmark "$@" ;;
    *)
        echo "Usage:"
        echo "  $0 compile                              # Compile only"
        echo "  $0 tpch --host ... [options]            # TPC-H only"
        echo "  $0 tpcc --host ... [options]            # TPC-C only"
        echo "  $0 all --host ...   [options]           # TPC-H + TPC-C"
        exit 1
        ;;
esac
