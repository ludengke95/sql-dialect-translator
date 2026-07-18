#!/usr/bin/env python3
"""
TPC-C 数据加载器（MySQL 后端）

复用同目录 data_gen.py 中的 TPC-C 标准数据生成器，将生成的数据写入 MySQL。
用于 SDTP PG 前端集成测试：SDTP 伪装为 PostgreSQL 服务端（前端=PG），
将 SQL 翻译转发到 MySQL 后端（backend=MySQL），数据落在本文件创建的 MySQL 表中。

用法:
    python load_mysql.py --mysql-host 127.0.0.1 --mysql-port 3306 \
        --mysql-user root --mysql-password secret --mysql-db tpcc --warehouses 1
"""
import argparse
import os
import sys

import pymysql

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from data_gen import TpccDataGenerator  # noqa: E402


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


def load(conn, num_warehouses):
    gen = TpccDataGenerator(num_warehouses)

    # 清空（子表优先）
    clear_tables(conn, ['order_line', 'new_orders', 'orders', 'history',
                        'customer', 'stock', 'item', 'district', 'warehouse'])

    # warehouse
    insert_rows(conn, 'warehouse',
                ['w_id', 'w_name', 'w_street_1', 'w_street_2', 'w_city', 'w_state',
                 'w_zip', 'w_tax', 'w_ytd'],
                gen.generate_warehouse())

    # district
    insert_rows(conn, 'district',
                ['d_id', 'd_w_id', 'd_name', 'd_street_1', 'd_street_2', 'd_city',
                 'd_state', 'd_zip', 'd_tax', 'd_ytd', 'd_next_o_id'],
                gen.generate_district())

    # item (100K, 跨仓库共享)
    insert_rows(conn, 'item',
                ['i_id', 'i_im_id', 'i_name', 'i_price', 'i_data'],
                gen.generate_item())

    # stock (每个仓库 100K)
    insert_rows(conn, 'stock',
                ['s_i_id', 's_w_id', 's_quantity',
                 's_dist_01', 's_dist_02', 's_dist_03', 's_dist_04', 's_dist_05',
                 's_dist_06', 's_dist_07', 's_dist_08', 's_dist_09', 's_dist_10',
                 's_ytd', 's_order_cnt', 's_remote_cnt', 's_data'],
                gen.generate_stock())

    # customer (每 district 3000)
    insert_rows(conn, 'customer',
                ['c_id', 'c_d_id', 'c_w_id', 'c_first', 'c_middle', 'c_last',
                 'c_street_1', 'c_street_2', 'c_city', 'c_state', 'c_zip', 'c_phone',
                 'c_since', 'c_credit', 'c_credit_lim', 'c_discount', 'c_balance',
                 'c_ytd_payment', 'c_payment_cnt', 'c_delivery_cnt', 'c_data'],
                gen.generate_customer())

    # orders + new_orders + order_line
    order_rows, no_rows, ol_rows = gen.generate_orders_and_new_orders()
    insert_rows(conn, 'orders',
                ['o_id', 'o_d_id', 'o_w_id', 'o_c_id', 'o_entry_d', 'o_carrier_id',
                 'o_ol_cnt', 'o_all_local'],
                order_rows)
    insert_rows(conn, 'new_orders',
                ['no_o_id', 'no_d_id', 'no_w_id'],
                no_rows)
    insert_rows(conn, 'order_line',
                ['ol_o_id', 'ol_d_id', 'ol_w_id', 'ol_number', 'ol_i_id', 'ol_supply_w_id',
                 'ol_delivery_d', 'ol_quantity', 'ol_amount', 'ol_dist_info'],
                ol_rows)

    # history
    insert_rows(conn, 'history',
                ['h_c_id', 'h_c_d_id', 'h_c_w_id', 'h_d_id', 'h_w_id', 'h_date',
                 'h_amount', 'h_data'],
                gen.generate_history())


def main():
    parser = argparse.ArgumentParser(description='TPC-C MySQL 数据加载器')
    parser.add_argument('--mysql-host', default='127.0.0.1')
    parser.add_argument('--mysql-port', type=int, default=3306)
    parser.add_argument('--mysql-user', default='root')
    parser.add_argument('--mysql-password', default='')
    parser.add_argument('--mysql-db', default='tpcc')
    parser.add_argument('--warehouses', type=int, default=1,
                        help='Warehouse 数量（默认 1）')
    parser.add_argument('--schema', default='tpcc',
                        help='兼容 data_gen 参数；MySQL 中忽略（表直接建在 mysql-db 下）')
    args = parser.parse_args()

    conn = pymysql.connect(host=args.mysql_host, port=args.mysql_port,
                           user=args.mysql_user, password=args.mysql_password,
                           database=args.mysql_db, charset='utf8mb4')
    try:
        print(f"TPC-C 数据加载 -> MySQL {args.mysql_host}:{args.mysql_port}/{args.mysql_db} ({args.warehouses} warehouse)")
        load(conn, args.warehouses)

        # 校验数据量
        with conn.cursor() as cur:
            for t in ['warehouse', 'district', 'item', 'stock', 'customer',
                      'orders', 'new_orders', 'order_line', 'history']:
                cur.execute(f"SELECT COUNT(*) FROM {t}")
                print(f"  {t}: {cur.fetchone()[0]} 行")
    finally:
        conn.close()


if __name__ == '__main__':
    main()
