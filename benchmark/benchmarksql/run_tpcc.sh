#!/bin/bash
set -e

# =============================================================================
# SDTP TPC-C BenchmarkRunner 脚本 (使用 BenchmarkSQL v5.0)
# =============================================================================

MODE=$1
DRIVER_VERSION=$2 # 用于 run-sdtp-mysql 模式，支持 '5' 或 '8'

# 默认环境变量
export PG_HOST=${PG_HOST:-"localhost"}
export PG_PORT=${PG_PORT:-"5432"}
export PG_DB=${PG_DB:-"mydb"}
export PG_USER=${PG_USER:-"sdtpu"}
export PG_PASSWORD=${PG_PASSWORD:-"pg_password"}

export SDTP_HOST=${SDTP_HOST:-"localhost"}
export SDTP_PORT=${SDTP_PORT:-"3306"}
export SDTP_USER=${SDTP_USER:-"root"}
export SDTP_PASSWORD=${SDTP_PASSWORD:-"proxy_password"}

# 模板与配置文件的基准路径 (WORKDIR 已设在 run/)
TPL_DIR="/opt/benchmarksql/run"
LOG_DIR="/opt/benchmarksql/run"

# 1. 动态生成配置文件
if [ "$MODE" = "build-pg" ] || [ "$MODE" = "run-pg" ]; then
    echo "正在基于 props.pg.tpl 生成 props.pg 配置文件..."
    python3 -c "
import os
content = open('${TPL_DIR}/props.pg.tpl').read()
for var in ['PG_HOST', 'PG_PORT', 'PG_DB', 'PG_USER', 'PG_PASSWORD']:
    content = content.replace('\${' + var + '}', os.environ.get(var, ''))
open('${TPL_DIR}/props.pg', 'w').write(content)
"
fi

if [ "$MODE" = "run-sdtp-mysql" ]; then
    echo "正在基于 props.mysql.tpl 生成 props.mysql 配置文件..."
    if [ "$DRIVER_VERSION" = "5" ]; then
        export MYSQL_DRIVER="com.mysql.jdbc.Driver"
    else
        export MYSQL_DRIVER="com.mysql.cj.jdbc.Driver"
    fi
    python3 -c "
import os
content = open('${TPL_DIR}/props.mysql.tpl').read()
for var in ['SDTP_HOST', 'SDTP_PORT', 'PG_DB', 'SDTP_USER', 'SDTP_PASSWORD', 'MYSQL_DRIVER']:
    content = content.replace('\${' + var + '}', os.environ.get(var, ''))
open('${TPL_DIR}/props.mysql', 'w').write(content)
"
fi

# 2. 执行操作
if [ "$MODE" = "build-pg" ]; then
    echo "=== 开始 TPC-C 数据库构建 (PostgreSQL) ==="

    echo "正在等待 PostgreSQL ($PG_HOST:$PG_PORT) 就绪..."
    until pg_isready -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB"; do
        sleep 1
    done

    echo "1. 创建 TPC-C 表结构..."
    ./runSQL.sh props.pg tableCreates

    echo "2. 多线程装载数据 (warehouses = 2)..."
    ./runLoader.sh props.pg

    echo "3. 创建索引与外键约束..."
    ./runSQL.sh props.pg indexCreates

    echo "=== TPC-C 数据构建完成 ==="

elif [ "$MODE" = "run-pg" ]; then
    echo "=== 开始 TPC-C 直连 PG 基线压测 (Baseline) ==="
    ./runBenchmark.sh props.pg > ${LOG_DIR}/run_pg.log 2>&1 || true
    echo "=== TPC-C 直连 PG 基线压测完成 ==="
    cat ${LOG_DIR}/run_pg.log | grep -i "tpmC" || true

elif [ "$MODE" = "run-sdtp-mysql" ]; then
    echo "=== 开始 TPC-C 连 SDTP (MySQL) 压测 ==="
    echo "正在等待 SDTP 代理 ($SDTP_HOST:$SDTP_PORT) 就绪..."
    python3 -c "
import socket, time
while True:
    try:
        s = socket.create_connection(('$SDTP_HOST', int('$SDTP_PORT')), timeout=1)
        s.close()
        break
    except Exception:
        time.sleep(1)
"
    ./runBenchmark.sh props.mysql > ${LOG_DIR}/run_mysql.log 2>&1 || true
    echo "=== TPC-C 连 SDTP (MySQL) 压测完成 ==="
    cat ${LOG_DIR}/run_mysql.log | grep -i "tpmC" || true

else
    echo "未知模式: $MODE"
    exit 1
fi
