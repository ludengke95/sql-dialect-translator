#!/usr/bin/env python3
"""
TPC-C 数据生成器（1 Warehouse 小数据量）

生成 TPC-C 标准 9 张表的数据并加载到 PostgreSQL。

用法:
    python data_gen.py --pg-host localhost --pg-port 5432 --pg-user pg_user --pg-password pg_password --pg-db mydb
    python data_gen.py --warehouses 1
"""

import argparse
import csv
import io
import math
import random
import string
from datetime import datetime, timedelta


class TpccDataGenerator:
    """TPC-C 数据生成器。"""

    # 客户姓氏列表（TPC-C 标准）
    C_LAST = [
        "BAR", "OUGHT", "ABLE", "PRI", "PRES", "ESE", "ANTI", "CALLY", "ATION", "EING",
        "BARBAR", "OUGHTBAR", "ABLEBAR", "PRIBAR", "PRESBAR", "ESEBAR", "ANTIBAR",
        "CALLYBAR", "ATIONBAR", "EINGBAR",
    ]

    # 客户名字
    C_FIRST = [
        "Chris", "John", "Jane", "Mary", "Mike", "Lisa", "Tom", "Ann", "David", "Sue",
        "Alan", "Beth", "Carl", "Dawn", "Eric", "Faye", "Gary", "Hope", "Ivan", "Jill",
    ]

    # 商品名称
    I_NAMES = [
        "Widget", "Gadget", "Doohickey", "Thingy", "Whatsit",
        "Doodad", "Gizmo", "Contraption", "Apparatus", "Device",
    ]

    # 地址数据
    STREETS = ["Main", "Oak", "Elm", "Park", "Broadway", "High", "Maple", "Cedar", "Pine", "Lake"]
    CITIES = ["Springfield", "Riverside", "Centerville", "Fairview", "Oakland"]
    STATES = ["CA", "NY", "TX", "FL", "IL", "PA", "OH", "GA", "NC", "MI"]

    def __init__(self, num_warehouses: int = 1):
        self.num_warehouses = num_warehouses
        # 使用固定种子确保可重复
        self.rng = random.Random(42)

    def _rand_str(self, length: int, rng: random.Random = None) -> str:
        r = rng or self.rng
        chars = string.ascii_letters + string.digits
        return ''.join(r.choice(chars) for _ in range(length))

    def _rand_phone(self, rng: random.Random = None) -> str:
        r = rng or self.rng
        return f"({r.randint(100, 999)}){r.randint(100, 999)}-{r.randint(1000, 9999)}"

    def _rand_zip(self, rng: random.Random = None) -> str:
        r = rng or self.rng
        return f"{r.randint(10000, 99999)}-{r.randint(1000, 9999)}"

    def _rand_address(self, rng: random.Random = None):
        r = rng or self.rng
        return (
            f"{r.randint(10, 9999)} {r.choice(self.STREETS)}",
            f"Suite {r.randint(1, 999)}",
            r.choice(self.CITIES),
            r.choice(self.STATES),
            self._rand_zip(r),
        )

    def _nurand(self, a: int, x: int, y: int, rng: random.Random = None) -> int:
        """NURand 函数 — TPC-C 标准随机数生成。"""
        r = rng or self.rng
        return ((r.randint(0, a) | r.randint(x, y)) + r.randint(0, a)) % (y - x + 1) + x

    def generate_warehouse(self):
        rows = []
        for w_id in range(1, self.num_warehouses + 1):
            rng = random.Random(w_id * 100)
            s1, s2, city, state, zip_code = self._rand_address(rng)
            rows.append((
                w_id,
                f"Warehouse{w_id:04d}",
                s1, s2, city, state, zip_code,
                round(rng.uniform(0.0, 0.2), 4),
                300000.00,  # w_ytd
            ))
        return rows

    def generate_district(self):
        rows = []
        for w_id in range(1, self.num_warehouses + 1):
            for d_id in range(1, 11):  # 每个 warehouse 10 个 district
                rng = random.Random(w_id * 1000 + d_id)
                s1, s2, city, state, zip_code = self._rand_address(rng)
                rows.append((
                    d_id, w_id,
                    f"District{d_id:02d}",
                    s1, s2, city, state, zip_code,
                    round(rng.uniform(0.0, 0.2), 4),
                    30000.00,  # d_ytd
                    3001,      # d_next_o_id
                ))
        return rows

    def generate_item(self):
        rows = []
        rng = random.Random(999)
        for i_id in range(1, 100001):  # 100K items (TPC-C 标准)
            name = rng.choice(self.I_NAMES) + " " + self._rand_str(8, rng)
            price = round(rng.uniform(1.0, 100.0), 2)
            # i_data: 20% 有 "ORIGINAL"
            if rng.random() < 0.2:
                data = "ORIGINAL " + self._rand_str(40, rng)
            else:
                data = self._rand_str(48, rng)
            rows.append((i_id, rng.randint(1, 10000), name, price, data))
        return rows

    def generate_stock(self):
        rows = []
        for w_id in range(1, self.num_warehouses + 1):
            rng = random.Random(w_id * 2000)
            for i_id in range(1, 100001):  # 每个商品在每个仓库都有库存
                qty = rng.randint(10, 100)
                dists = [self._rand_str(24, rng) for _ in range(10)]
                data = self._rand_str(48, rng)
                rows.append((
                    i_id, w_id, qty,
                    *dists,
                    0.00, 0, 0, data,
                ))
        return rows

    def generate_customer(self):
        rows = []
        for w_id in range(1, self.num_warehouses + 1):
            for d_id in range(1, 11):
                rng = random.Random(w_id * 10000 + d_id * 100)
                for c_id in range(1, 3001):  # 每个 district 3000 客户
                    # 10% 客户有 "BC" 信用等级
                    credit = "BC" if rng.random() < 0.1 else "GC"
                    last_idx = self._nurand(255, 0, 999, rng)
                    last_name = self.C_LAST[last_idx % len(self.C_LAST)]
                    first_name = rng.choice(self.C_FIRST)
                    middle = "OE"
                    s1, s2, city, state, zip_code = self._rand_address(rng)
                    rows.append((
                        c_id, d_id, w_id,
                        first_name, middle, last_name,
                        s1, s2, city, state, zip_code,
                        self._rand_phone(rng),
                        datetime.now(),
                        credit,
                        50000.00,
                        round(rng.uniform(0.0, 0.5), 4),
                        -10.00, 10.00, 1, 0,
                        self._rand_str(498, rng),
                    ))
        return rows

    def generate_orders_and_new_orders(self):
        order_rows = []
        new_order_rows = []
        order_line_rows = []

        for w_id in range(1, self.num_warehouses + 1):
            for d_id in range(1, 11):
                rng = random.Random(w_id * 100000 + d_id * 1000)

                # 为每个 district 生成 3000 个订单
                # 前 900 个订单进入 new_orders（未发货）
                # 注意：TPC-C 要求 o_c_id 是随机排列的客户 ID
                customer_ids = list(range(1, 3001))
                rng.shuffle(customer_ids)

                for o_id in range(1, 3001):
                    c_id = customer_ids[o_id - 1]
                    ol_cnt = rng.randint(5, 15)  # 5-15 个商品每单
                    carrier_id = None if o_id <= 900 else rng.randint(1, 10)

                    row = (
                        o_id, d_id, w_id, c_id,
                        datetime.now(),
                        carrier_id,
                        ol_cnt,
                        0,  # all_local
                    )
                    order_rows.append(row)

                    if o_id <= 900:
                        new_order_rows.append((o_id, d_id, w_id))

                    # 生成 order_line
                    for ol_num in range(1, ol_cnt + 1):
                        i_id = rng.randint(1, 100000)
                        ol_amount = round(rng.uniform(0.01, 9999.99), 2)
                        ol_delivery = None if o_id <= 900 else datetime.now()
                        order_line_rows.append((
                            o_id, d_id, w_id, ol_num,
                            i_id, w_id,
                            ol_delivery,
                            5, ol_amount,
                            self._rand_str(24, rng),
                        ))

        return order_rows, new_order_rows, order_line_rows

    def generate_history(self):
        rows = []
        for w_id in range(1, self.num_warehouses + 1):
            for d_id in range(1, 11):
                rng = random.Random(w_id * 1000000 + d_id * 10000)
                for c_id in range(1, 3001):
                    rows.append((
                        c_id, d_id, w_id, d_id, w_id,
                        datetime.now(),
                        round(rng.uniform(1.0, 5000.0), 2),
                        self._rand_str(22, rng),
                    ))
        return rows


def load_csv_to_pg(conn_kwargs, table_name, columns, rows, batch_size=500):
    import psycopg2
    conn = psycopg2.connect(**conn_kwargs)
    try:
        cur = conn.cursor()
        buf = io.StringIO()
        writer = csv.writer(buf)
        for row in rows:
            writer.writerow(row)
        buf.seek(0)
        cur.copy_from(buf, table_name, sep=',', null='', columns=columns)
        conn.commit()
        print(f"  导入 {table_name}: {len(rows)} 行")
    finally:
        conn.close()


def main():
    parser = argparse.ArgumentParser(description='TPC-C 数据生成器')
    parser.add_argument('--pg-host', default='localhost')
    parser.add_argument('--pg-port', type=int, default=5432)
    parser.add_argument('--pg-user', default='pg_user')
    parser.add_argument('--pg-password', default='pg_password')
    parser.add_argument('--pg-db', default='mydb')
    parser.add_argument('--warehouses', type=int, default=1,
                        help='Warehouse 数量（默认 1）')
    args = parser.parse_args()

    conn_kwargs = {
        'host': args.pg_host,
        'port': args.pg_port,
        'user': args.pg_user,
        'password': args.pg_password,
        'dbname': args.pg_db,
    }

    nw = args.warehouses
    print(f"TPC-C 数据生成 ({nw} warehouse)")
    print(f"  目标: {args.pg_host}:{args.pg_port}/{args.pg_db}")

    gen = TpccDataGenerator(nw)

    import time
    t0 = time.time()

    # warehouse
    rows = gen.generate_warehouse()
    load_csv_to_pg(conn_kwargs, 'warehouse', ['w_id','w_name','w_street_1','w_street_2','w_city','w_state','w_zip','w_tax','w_ytd'], rows)

    # district
    rows = gen.generate_district()
    load_csv_to_pg(conn_kwargs, 'district', ['d_id','d_w_id','d_name','d_street_1','d_street_2','d_city','d_state','d_zip','d_tax','d_ytd','d_next_o_id'], rows)

    # item (100K, shared across warehouses)
    rows = gen.generate_item()
    load_csv_to_pg(conn_kwargs, 'item', ['i_id','i_im_id','i_name','i_price','i_data'], rows)

    # stock (100K per warehouse)
    rows = gen.generate_stock()
    load_csv_to_pg(conn_kwargs, 'stock', ['s_i_id','s_w_id','s_quantity',
        's_dist_01','s_dist_02','s_dist_03','s_dist_04','s_dist_05',
        's_dist_06','s_dist_07','s_dist_08','s_dist_09','s_dist_10',
        's_ytd','s_order_cnt','s_remote_cnt','s_data'], rows)

    # customer (3000 per district, 10 districts per warehouse)
    rows = gen.generate_customer()
    load_csv_to_pg(conn_kwargs, 'customer', ['c_id','c_d_id','c_w_id','c_first','c_middle','c_last',
        'c_street_1','c_street_2','c_city','c_state','c_zip','c_phone','c_since','c_credit',
        'c_credit_lim','c_discount','c_balance','c_ytd_payment','c_payment_cnt','c_delivery_cnt','c_data'], rows)

    # orders + new_orders + order_line
    order_rows, no_rows, ol_rows = gen.generate_orders_and_new_orders()
    load_csv_to_pg(conn_kwargs, 'orders', ['o_id','o_d_id','o_w_id','o_c_id','o_entry_d','o_carrier_id','o_ol_cnt','o_all_local'], order_rows)
    load_csv_to_pg(conn_kwargs, 'new_orders', ['no_o_id','no_d_id','no_w_id'], no_rows)
    load_csv_to_pg(conn_kwargs, 'order_line', ['ol_o_id','ol_d_id','ol_w_id','ol_number','ol_i_id','ol_supply_w_id','ol_delivery_d','ol_quantity','ol_amount','ol_dist_info'], ol_rows)

    # history
    rows = gen.generate_history()
    load_csv_to_pg(conn_kwargs, 'history', ['h_c_id','h_c_d_id','h_c_w_id','h_d_id','h_w_id','h_date','h_amount','h_data'], rows)

    t1 = time.time()
    print(f"\n数据生成完成，耗时 {t1-t0:.1f}s")

    # 验证
    import psycopg2
    conn = psycopg2.connect(**conn_kwargs)
    cur = conn.cursor()
    for table in ['warehouse','district','item','stock','customer','orders','new_orders','order_line','history']:
        cur.execute(f"SELECT COUNT(*) FROM {table}")
        cnt = cur.fetchone()[0]
        print(f"  {table}: {cnt} 行")
    conn.close()


if __name__ == '__main__':
    main()
