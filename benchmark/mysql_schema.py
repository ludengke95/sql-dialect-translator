#!/usr/bin/env python3
"""
MySQL 后端 schema 执行器（不依赖 runner 上的 mysql CLI）。

用 pymysql 连接 MySQL，先建库（若不存在），再按顺序执行 schema SQL 中的 DDL。
用于 SDTP PG 前端集成测试：后端=MySQL，建表由本脚本完成。

用法:
    python mysql_schema.py --mysql-host 127.0.0.1 --mysql-port 3306 \
        --mysql-user root --mysql-password secret --mysql-db tpch \
        --schema-file benchmark/tpch/schema_mysql.sql
"""
import argparse

import pymysql


def split_statements(sql):
    """按行切分 DDL，遇 ; 结束一条；跳过 -- 注释行。"""
    stmts = []
    buf = []
    for line in sql.splitlines():
        if line.strip().startswith('--'):
            continue
        buf.append(line)
        if line.strip().endswith(';'):
            s = '\n'.join(buf).strip()
            if s.endswith(';'):
                s = s[:-1].strip()
            if s:
                stmts.append(s)
            buf = []
    return stmts


def main():
    ap = argparse.ArgumentParser(description='MySQL schema 执行器')
    ap.add_argument('--mysql-host', default='127.0.0.1')
    ap.add_argument('--mysql-port', type=int, default=3306)
    ap.add_argument('--mysql-user', default='root')
    ap.add_argument('--mysql-password', default='')
    ap.add_argument('--mysql-db', required=True)
    ap.add_argument('--schema-file', required=True)
    args = ap.parse_args()

    # 先连默认库，确保目标库存在
    conn = pymysql.connect(host=args.mysql_host, port=args.mysql_port,
                           user=args.mysql_user, password=args.mysql_password,
                           charset='utf8mb4')
    try:
        with conn.cursor() as cur:
            cur.execute(
                f"CREATE DATABASE IF NOT EXISTS `{args.mysql_db}` CHARACTER SET utf8mb4")
        conn.commit()
    finally:
        conn.close()

    # 连目标库执行 schema
    conn = pymysql.connect(host=args.mysql_host, port=args.mysql_port,
                           user=args.mysql_user, password=args.mysql_password,
                           database=args.mysql_db, charset='utf8mb4')
    try:
        with open(args.schema_file, 'r', encoding='utf8') as f:
            sql = f.read()
        stmts = split_statements(sql)
        with conn.cursor() as cur:
            for st in stmts:
                cur.execute(st)
        conn.commit()
        print(f"  已执行 {len(stmts)} 条 DDL -> `{args.mysql_db}` (from {args.schema_file})")
    finally:
        conn.close()


if __name__ == '__main__':
    main()
