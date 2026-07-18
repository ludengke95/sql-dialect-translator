#!/usr/bin/env python3
"""
TPC-H 数据加载器（MySQL 后端）

复用同目录 data_gen.py 中的 TPC-H 标准数据生成器，将生成的数据写入 MySQL。
用于 SDTP PG 前端集成测试：SDTP 伪装为 PostgreSQL 服务端（前端=PG），
将 SQL 翻译转发到 MySQL 后端（backend=MySQL），数据落在本文件创建的 MySQL 表中。

用法:
    python load_mysql.py --mysql-host 127.0.0.1 --mysql-port 3306 \
        --mysql-user root --mysql-password secret --mysql-db tpch --scale 0.01
"""
import argparse
import os
import sys

import pymysql

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from data_gen import TpchDataGenerator  # noqa: E402

SCALE_DEFAULT = 0.01


def insert_rows(conn, table, columns, rows, batch_size=5000):
    """用 pymysql executemany 分批写入 MySQL。"""
    placeholders = ', '.join(['%s'] * len(columns))
    cols = ', '.join(columns)
    sql = f"INSERT INTO {table} ({cols}) VALUES ({placeholders})"
    with conn.cursor() as cur:
        for i in range(0, len(rows), batch_size):
            batch = rows[i:i + batch_size]
            cur.executemany(sql, batch)
    conn.commit()
    print(f"  导入 {table}: {len(rows)} 行")


def clear_tables(conn, tables):
    """清空表（子表优先，避免 FK 约束报错，便于重复运行）。"""
    with conn.cursor() as cur:
        for t in tables:
            cur.execute(f"DELETE FROM {t}")
    conn.commit()


def load(conn, scale):
    gen = TpchDataGenerator(scale)
    # 清空（子表优先）
    clear_tables(conn, ['lineitem', 'orders', 'customer', 'partsupp', 'part',
                        'supplier', 'nation', 'region'])

    specs = [
        ('region', ['r_regionkey', 'r_name', 'r_comment'], gen.generate_region),
        ('nation', ['n_nationkey', 'n_name', 'n_regionkey', 'n_comment'], gen.generate_nation),
        ('supplier', ['s_suppkey', 's_name', 's_address', 's_nationkey', 's_phone',
                      's_acctbal', 's_comment'], gen.generate_supplier),
        ('part', ['p_partkey', 'p_name', 'p_mfgr', 'p_brand', 'p_type', 'p_size',
                  'p_container', 'p_retailprice', 'p_comment'], gen.generate_part),
        ('partsupp', ['ps_partkey', 'ps_suppkey', 'ps_availqty', 'ps_supplycost', 'ps_comment'],
         gen.generate_partsupp),
        ('customer', ['c_custkey', 'c_name', 'c_address', 'c_nationkey', 'c_phone',
                      'c_acctbal', 'c_mktsegment', 'c_comment'], gen.generate_customer),
        ('orders', ['o_orderkey', 'o_custkey', 'o_orderstatus', 'o_totalprice',
                    'o_orderdate', 'o_orderpriority', 'o_clerk', 'o_shippriority', 'o_comment'],
         gen.generate_orders),
        ('lineitem', ['l_orderkey', 'l_partkey', 'l_suppkey', 'l_linenumber', 'l_quantity',
                      'l_extendedprice', 'l_discount', 'l_tax', 'l_returnflag', 'l_linestatus',
                      'l_shipdate', 'l_commitdate', 'l_receiptdate', 'l_shipinstruct',
                      'l_shipmode', 'l_comment'], gen.generate_lineitem),
    ]
    for table, cols, gen_func in specs:
        insert_rows(conn, table, cols, gen_func())


def main():
    parser = argparse.ArgumentParser(description='TPC-H MySQL 数据加载器')
    parser.add_argument('--mysql-host', default='127.0.0.1')
    parser.add_argument('--mysql-port', type=int, default=3306)
    parser.add_argument('--mysql-user', default='root')
    parser.add_argument('--mysql-password', default='')
    parser.add_argument('--mysql-db', default='tpch')
    parser.add_argument('--scale', type=float, default=SCALE_DEFAULT,
                        help='Scale factor (default: 0.01)')
    parser.add_argument('--schema', default='tpch',
                        help='兼容 data_gen 参数；MySQL 中忽略（表直接建在 mysql-db 下）')
    args = parser.parse_args()

    conn = pymysql.connect(host=args.mysql_host, port=args.mysql_port,
                           user=args.mysql_user, password=args.mysql_password,
                           database=args.mysql_db, charset='utf8mb4')
    try:
        print(f"TPC-H 数据加载 -> MySQL {args.mysql_host}:{args.mysql_port}/{args.mysql_db} (SF={args.scale})")
        load(conn, args.scale)

        # 校验数据量
        with conn.cursor() as cur:
            for t in ['region', 'nation', 'supplier', 'part', 'partsupp',
                      'customer', 'orders', 'lineitem']:
                cur.execute(f"SELECT COUNT(*) FROM {t}")
                print(f"  {t}: {cur.fetchone()[0]} 行")
    finally:
        conn.close()


if __name__ == '__main__':
    main()
