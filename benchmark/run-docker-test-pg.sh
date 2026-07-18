#!/bin/bash
# =============================================================================
# run-docker-test-pg.sh — 以 PostgreSQL 客户端协议对 SDTP（前端=PG）执行集成测试
#
# 与 run-docker-test.sh 互为反向：客户端用 PG 协议（psql / psycopg2）连接 SDTP，
# SDTP 将 SQL 翻译为 MySQL 转发到后端。SDTP 在反向测试中伪装为 PostgreSQL 服务端。
#
# 支持模式:
#   pg-client  : Docker postgres 原生 psql 客户端
#   python-pg  : Docker python-pg (psycopg2)
#
# 基准集:
#   tpch / tpcc / all  — 复用 benchmark/<bm>/queries 下的查询
#                        注意：现有查询为 MySQL 方言，前端=PG 时需 PG 方言版本
#                        （见 docs/pg-mock/TODO.md），当前默认建议用 smoke 验证链路
#   smoke     — 内置少量 PG 方言冒烟查询（SELECT/JOIN/INSERT），可直接跑通
#
# 用法:
#   bash run-docker-test-pg.sh \
#     --mode python-pg \
#     --host host.docker.internal --port 5432 \
#     --user sdtpu --password pg_password \
#     --database tpch \
#     --benchmark smoke \
#     --output-dir benchmark/reports
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ========================= 默认参数 =========================
MODE=""
HOST="host.docker.internal"
PORT=5432
USER="sdtpu"
PASSWORD="pg_password"
DATABASE="tpch"
BENCHMARK="smoke"
TPCH_QUERIES=""
TPCC_QUERIES=""
OUTPUT_DIR="."
TIMEOUT=60
ADD_HOST="true"

# ========================= 帮助信息 =========================
usage() {
    cat << 'EOF'
用法: run-docker-test-pg.sh [选项]

选项:
  --mode MODE          测试模式（必填）: pg-client / python-pg
  --host HOST          目标 PG 主机（默认: host.docker.internal）
  --port PORT          目标 PG 端口（默认: 5432）
  --user USER          PG 用户名（默认: sdtpu）
  --password PASSWORD  PG 密码（默认: pg_password）
  --database DB        数据库名（默认: tpch）
  --benchmark TYPE     测试集: tpch / tpcc / all / smoke（默认: smoke）
  --tpch-queries PATH  TPC-H 查询目录（默认自动检测）
  --tpcc-queries PATH  TPC-C 查询目录（默认自动检测）
  --output-dir DIR     报告输出目录（默认: .）
  --timeout SEC        单条查询超时秒数（默认: 60）
  --no-add-host        禁用 --add-host（Docker Desktop 不需要）
  --help               显示帮助信息
EOF
    exit 0
}

# ========================= 参数解析 =========================
while [[ $# -gt 0 ]]; do
    case "$1" in
        --mode)          MODE="$2"; shift 2 ;;
        --host)          HOST="$2"; shift 2 ;;
        --port)          PORT="$2"; shift 2 ;;
        --user)          USER="$2"; shift 2 ;;
        --password)      PASSWORD="$2"; shift 2 ;;
        --database)      DATABASE="$2"; shift 2 ;;
        --benchmark)     BENCHMARK="$2"; shift 2 ;;
        --tpch-queries)  TPCH_QUERIES="$2"; shift 2 ;;
        --tpcc-queries)  TPCC_QUERIES="$2"; shift 2 ;;
        --output-dir)    OUTPUT_DIR="$2"; shift 2 ;;
        --timeout)       TIMEOUT="$2"; shift 2 ;;
        --no-add-host)   ADD_HOST="false"; shift ;;
        --help)          usage ;;
        *) echo "未知参数: $1"; usage ;;
    esac
done

if [[ -z "$MODE" ]]; then
    echo "错误: 必须指定 --mode"
    usage
fi

case "$MODE" in
    pg-client) DOCKER_IMAGE="postgres:15-alpine" ;;
    python-pg) DOCKER_IMAGE="python-pg:latest" ;;
    *)
        echo "错误: 不支持的 mode: $MODE"
        echo "  支持: pg-client, python-pg"
        exit 1
        ;;
esac

BASE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TPCH_QUERIES="${TPCH_QUERIES:-$SCRIPT_DIR/tpch/queries}"
TPCC_QUERIES="${TPCC_QUERIES:-$SCRIPT_DIR/tpcc/queries}"

mkdir -p "$OUTPUT_DIR"

DOCKER_BASE_FLAGS="--rm"
if [[ "$ADD_HOST" == "true" ]]; then
    DOCKER_BASE_FLAGS="$DOCKER_BASE_FLAGS --add-host host.docker.internal:host-gateway"
fi

# ========================= 读取查询 =========================
read_queries_from_dir() {
    local dir="$1"
    local -n result_ref="$2"
    if [[ ! -d "$dir" ]]; then
        echo "  警告: 查询目录不存在: $dir"
        return 1
    fi
    result_ref=()
    local files
    mapfile -t files < <(find "$dir" -maxdepth 1 -name '*.sql' -type f | sort)
    for f in "${files[@]}"; do
        local name
        name="$(basename "$f" .sql)"
        local sql
        sql="$(cat "$f")"
        if [[ -z "${sql// }" ]]; then
            continue
        fi
        result_ref+=("${name}|${sql}")
    done
}

# 内置 PG 方言冒烟查询（可直接被 SDTP 翻译为 MySQL 跑通）
SMOKE_QUERIES=(
    "smoke_count_region|SELECT count(*) AS cnt FROM region"
    "smoke_region_name|SELECT r_name FROM region WHERE r_regionkey = 0"
    "smoke_join|SELECT n_name, r_name FROM nation JOIN region ON nation.n_regionkey = region.r_regionkey WHERE r_regionkey = 1"
    "smoke_insert|INSERT INTO nation (n_nationkey, n_name, n_regionkey, n_comment) VALUES (98, 'SMOKE', 0, 'pg-frontend smoke test')"
    "smoke_insert_select|SELECT n_name FROM nation WHERE n_nationkey = 98"
)

# ========================= 执行 SQL =========================
_EXEC_RC=0
_EXEC_OUTPUT=""
_EXEC_DURATION=0

execute_sql() {
    local sql="$1"
    local db="${2:-$DATABASE}"
    local start_time end_time

    _EXEC_RC=0
    _EXEC_OUTPUT=""
    _EXEC_DURATION=0

    start_time="$(date +%s.%N)"

    case "$MODE" in
        pg-client)
            _EXEC_OUTPUT="$(
                PGPASSWORD="$PASSWORD" docker run $DOCKER_BASE_FLAGS \
                    "$DOCKER_IMAGE" \
                    psql -h "$HOST" -p "$PORT" --protocol TCP \
                    -U "$USER" -d "$db" \
                    -c "$sql" 2>&1
            )" || _EXEC_RC=$?
            ;;
        python-pg)
            _EXEC_OUTPUT="$(
                docker run $DOCKER_BASE_FLAGS \
                    -e PG_HOST="$HOST" \
                    -e PG_PORT="$PORT" \
                    -e PG_USER="$USER" \
                    -e PG_PASSWORD="$PASSWORD" \
                    -e PG_DATABASE="$db" \
                    -e SQL_STATEMENTS="$sql" \
                    "$DOCKER_IMAGE" 2>&1
            )" || _EXEC_RC=$?
            ;;
    esac

    end_time="$(date +%s.%N)"
    _EXEC_DURATION="$(echo "scale=4; ($end_time - $start_time)" | bc 2>/dev/null || echo "0")"
}

# ========================= 判断执行是否成功 =========================
is_success() {
    local rc="$1"
    local output="$2"

    # python-pg 输出 JSON，直接解析 success 字段
    if [[ "$MODE" == "python-pg" ]]; then
        local success_val
        success_val="$(echo "$output" | jq -r '.success' 2>/dev/null)"
        if [[ "$success_val" == "true" ]]; then
            return 0
        else
            return 1
        fi
    fi

    if [[ "$rc" -ne 0 ]]; then
        return 1
    fi

    # psql 出错时输出 ERROR / ERROR: / FATAL
    if echo "$output" | grep -qE 'ERROR:|FATAL:|psycopg2'; then
        return 1
    fi

    return 0
}

# ========================= 运行一组查询 =========================
run_benchmark() {
    local label="$1"
    local -n queries_ref="$2"
    local passed=0
    local failed=0

    echo "===== 基准集: $label (${#queries_ref[@]} 条) ====="

    local i=1
    for entry in "${queries_ref[@]}"; do
        local name="${entry%%|*}"
        local sql="${entry#*|}"

        execute_sql "$sql"

        local ok=0
        if is_success "$_EXEC_RC" "$_EXEC_OUTPUT"; then
            ok=1
            passed=$((passed + 1))
            echo "  [PASS] #$i $name (${_EXEC_DURATION}s)"
        else
            failed=$((failed + 1))
            echo "  [FAIL] #$i $name (${_EXEC_DURATION}s)"
            echo "        ${_EXEC_OUTPUT:0:300}"
        fi

        i=$((i + 1))
    done

    local total=$((passed + failed))
    local report="$OUTPUT_DIR/report_${label}.txt"
    {
        echo "基准集: $label"
        echo "总计: $total  通过: $passed  失败: $failed"
        echo "通过率: $(( total > 0 ? passed * 100 / total : 0 ))%"
    } > "$report"

    echo "  通过率: $passed/$total"
}

# ========================= 主流程 =========================
declare -a TPCH_LIST=()
declare -a TPCC_LIST=()

case "$BENCHMARK" in
    smoke)
        run_benchmark "smoke" SMOKE_QUERIES
        ;;
    tpch)
        read_queries_from_dir "$TPCH_QUERIES" TPCH_LIST
        run_benchmark "tpch" TPCH_LIST
        ;;
    tpcc)
        read_queries_from_dir "$TPCC_QUERIES" TPCC_LIST
        run_benchmark "tpcc" TPCC_LIST
        ;;
    all)
        read_queries_from_dir "$TPCH_QUERIES" TPCH_LIST
        run_benchmark "tpch" TPCH_LIST
        read_queries_from_dir "$TPCC_QUERIES" TPCC_LIST
        run_benchmark "tpcc" TPCC_LIST
        ;;
    *)
        echo "错误: 不支持的 benchmark: $BENCHMARK"
        exit 1
        ;;
esac

echo "完成。"
