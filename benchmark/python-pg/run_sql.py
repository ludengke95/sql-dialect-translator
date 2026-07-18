#!/usr/bin/env python3
"""
容器内多语句 PostgreSQL SQL 执行器（前端=PG 协议接入 SDTP）。

通过环境变量接收连接参数和 SQL，逐条执行，输出 JSON 格式结果。
与 benchmark/python-mysql/run_sql.py 保持相同的 JSON 契约，便于复用报告解析。

环境变量:
  PG_HOST       — PostgreSQL 主机（默认: host.docker.internal）
  PG_PORT       — PostgreSQL 端口（默认: 5432）
  PG_USER       — 用户名（默认: sdtpu）
  PG_PASSWORD   — 密码（默认: 空）
  PG_DATABASE   — 数据库名（默认: tpch）
  SQL_STATEMENTS— 要执行的 SQL 文本，支持多条语句（以 ; 分隔）

输出 (stdout):
  {"success": true/false, "duration": 0.0234, "error": "..."}
"""

import json
import os
import sys
import time

import psycopg2
import psycopg2.extras


def parse_statements(sql_text):
    """将 SQL 文本按 ; 分割为多条语句，过滤空语句和纯注释行。"""
    statements = []
    current = []

    for line in sql_text.split('\n'):
        stripped = line.strip()

        if not stripped:
            continue
        if stripped.startswith('--'):
            continue

        current.append(line)

        if stripped.endswith(';'):
            stmt = '\n'.join(current).strip()
            if stmt.endswith(';'):
                stmt = stmt[:-1].strip()
            if stmt:
                statements.append(stmt)
            current = []

    leftover = '\n'.join(current).strip()
    if leftover:
        if leftover.endswith(';'):
            leftover = leftover[:-1].strip()
        if leftover:
            statements.append(leftover)

    return statements


def main():
    host = os.environ.get('PG_HOST', 'host.docker.internal')
    port = int(os.environ.get('PG_PORT', '5432'))
    user = os.environ.get('PG_USER', 'sdtpu')
    password = os.environ.get('PG_PASSWORD', '')
    database = os.environ.get('PG_DATABASE', 'tpch')
    sql_text = os.environ.get('SQL_STATEMENTS', '')

    statements = parse_statements(sql_text)

    start = time.time()
    success = True
    error_msg = ''

    conn = None
    try:
        conn = psycopg2.connect(
            host=host, port=port, user=user, password=password,
            dbname=database, connect_timeout=10,
        )
        conn.autocommit = True
        with conn.cursor() as cur:
            for stmt in statements:
                try:
                    cur.execute(stmt)
                except Exception as e:  # noqa: BLE001 - 单条失败不影响整体结果判定
                    success = False
                    error_msg = "{} | {}".format(str(e).replace('\n', ' '), stmt[:120])
                    break
    except Exception as e:  # noqa: BLE001
        success = False
        error_msg = str(e).replace('\n', ' ')
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:  # noqa: BLE001
                pass

    duration = time.time() - start
    print(json.dumps({
        'success': success,
        'duration': round(duration, 4),
        'error': error_msg,
    }, ensure_ascii=False))


if __name__ == '__main__':
    main()
