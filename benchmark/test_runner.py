#!/usr/bin/env python3
"""
read_queries 单元测试。

用法:
    pytest benchmark/test_runner.py -v
    python -m pytest benchmark/test_runner.py -v
"""

import os
import sys
import tempfile

# 确保可以 import runner.py
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from runner import read_queries


def _make_sql_file(content: str) -> str:
    """创建临时 SQL 文件，返回路径。"""
    tmp = tempfile.NamedTemporaryFile(mode='w', suffix='.sql', delete=False, encoding='utf-8')
    tmp.write(content)
    tmp.close()
    return tmp.name


# ==================== TPC-H 格式测试 ====================

def test_tpch_q1_is_parsed():
    """TPC-H Q1: 多行 SELECT，-- END Q1 尾标。"""
    content = """-- 查询 Q1: 定价摘要报告
SELECT
    l_returnflag,
    l_linestatus,
    SUM(l_quantity) AS sum_qty
FROM
    lineitem
WHERE
    l_shipdate <= DATE_ADD('1998-12-01', INTERVAL -90 DAY)
GROUP BY
    l_returnflag,
    l_linestatus;
-- END Q1
"""
    path = _make_sql_file(content)
    try:
        queries = read_queries(path)
        assert len(queries) == 1, f"应为 1 组查询，实际: {len(queries)}"
        assert queries[0]['name'] == '查询 Q1: 定价摘要报告'
        assert 'SELECT' in queries[0]['sql'].upper()
        assert 'lineitem' in queries[0]['sql']
    finally:
        os.unlink(path)


def test_tpch_q22_with_trailing_label():
    """TPC-H Q22: -- END Q22 后无内容，分割后不应产生假查询。"""
    content = """-- 查询 Q22: 全球销售机会
SELECT
    cntrycode,
    COUNT(*) AS numcust,
    SUM(c_acctbal) AS totacctbal
FROM (
    SELECT SUBSTR(c_phone, 1, 2) AS cntrycode, c_acctbal
    FROM customer
    WHERE SUBSTR(c_phone, 1, 2) IN ('13', '31', '23', '29', '30', '18', '17')
) AS custsale
GROUP BY cntrycode
ORDER BY cntrycode;
-- END Q22
"""
    path = _make_sql_file(content)
    try:
        queries = read_queries(path)
        assert len(queries) == 1, f"应为 1 组查询，实际: {len(queries)}"
        assert queries[0]['name'] == '查询 Q22: 全球销售机会'
        assert 'SUBSTR' in queries[0]['sql']
    finally:
        os.unlink(path)


def test_tpch_all_22_queries():
    """完整 TPC-H 文件应解析出 22 组查询。"""
    base_dir = os.path.dirname(os.path.abspath(__file__))
    tpch_file = os.path.join(base_dir, 'tpch', 'queries_mysql.sql')
    if not os.path.exists(tpch_file):
        return  # 跳过（文件不存在时不报错）
    queries = read_queries(tpch_file)
    assert len(queries) == 22, f"TPC-H 应有 22 组查询，实际: {len(queries)}"
    # 验证 Q1 到 Q22 的名称
    names = [q['name'] for q in queries]
    for i in range(1, 23):
        assert any(f'Q{i}' in n for n in names), f"缺少 Q{i}"


# ==================== TPC-C 格式测试 ====================

def test_tpcc_transaction_multiple_statements():
    """TPC-C 事务块：一个 -- END 内包含多条 SQL（SELECT + INSERT）。"""
    content = """-- ==================== 事务 T1: New-Order ====================
-- T1-a: 查询仓库税率
SELECT w_tax FROM warehouse WHERE w_id = 1;
-- T1-b: 查询区域税率
SELECT d_tax FROM district WHERE d_w_id = 1 AND d_id = 1;
INSERT INTO orders (o_id, o_d_id, o_w_id, o_c_id, o_entry_d, o_carrier_id, o_ol_cnt, o_all_local)
VALUES (3001, 1, 1, 1, NOW(), NULL, 5, 0);
-- END New-Order
"""
    path = _make_sql_file(content)
    try:
        queries = read_queries(path)
        assert len(queries) == 1, f"应为 1 组查询，实际: {len(queries)}"
        assert queries[0]['name'] == '==================== 事务 T1: New-Order ===================='
        assert 'SELECT' in queries[0]['sql']
        assert 'INSERT' in queries[0]['sql']
    finally:
        os.unlink(path)


def test_tpcc_all_transactions():
    """完整 TPC-C 文件应解析出指定数量的事务块。"""
    base_dir = os.path.dirname(os.path.abspath(__file__))
    tpcc_file = os.path.join(base_dir, 'tpcc', 'queries_mysql.sql')
    if not os.path.exists(tpcc_file):
        return
    queries = read_queries(tpcc_file)
    # 5 类事务 + 3 个额外分析查询
    assert len(queries) >= 5, f"TPC-C 应至少 5 个事务块，实际: {len(queries)}"
    assert any('New-Order' in q['name'] for q in queries)
    assert any('Payment' in q['name'] for q in queries)
    assert any('Delivery' in q['name'] for q in queries)


# ==================== 边界情况测试 ====================

def test_empty_file():
    """空文件应返回空列表。"""
    path = _make_sql_file('')
    try:
        queries = read_queries(path)
        assert queries == []
    finally:
        os.unlink(path)


def test_only_comments():
    """只有注释的文件应返回空列表。"""
    content = """-- 查询 Q1: test
-- END Q1
-- 查询 Q2: another
-- END Q2
"""
    path = _make_sql_file(content)
    try:
        queries = read_queries(path)
        assert queries == [], f"只有注释时应返回空列表，实际: {len(queries)}"
    finally:
        os.unlink(path)


def test_trailing_label_after_end():
    """-- END Q22 分割后残留 ' Q22' 不应产生查询。"""
    content = """SELECT 1;
-- END Q1
Q22
"""
    path = _make_sql_file(content)
    try:
        queries = read_queries(path)
        assert len(queries) == 1
        assert 'SELECT 1' in queries[0]['sql']
    finally:
        os.unlink(path)


def test_blank_lines_between_blocks():
    """块之间的空行不应影响分割。"""
    content = """SELECT 1 AS a;
-- END Q1


SELECT 2 AS b;
-- END Q2
"""
    path = _make_sql_file(content)
    try:
        queries = read_queries(path)
        assert len(queries) == 2
        assert 'SELECT 1' in queries[0]['sql']
        assert 'SELECT 2' in queries[1]['sql']
    finally:
        os.unlink(path)


def test_insert_update_delete():
    """INSERT/UPDATE/DELETE 开头的块也应被识别。"""
    content = """-- T1: Insert
INSERT INTO t (id, name) VALUES (1, 'a');
-- END Insert

-- T2: Update
UPDATE t SET name = 'b' WHERE id = 1;
-- END Update

-- T3: Delete
DELETE FROM t WHERE id = 1;
-- END Delete
"""
    path = _make_sql_file(content)
    try:
        queries = read_queries(path)
        assert len(queries) == 3
        assert 'INSERT' in queries[0]['sql'].upper()
        assert 'UPDATE' in queries[1]['sql'].upper()
        assert 'DELETE' in queries[2]['sql'].upper()
    finally:
        os.unlink(path)


def test_non_sql_content_skipped():
    """不以 SQL 关键字开头的块（如纯文本）应跳过。"""
    content = """SELECT 1;
-- END Q1
some random text
-- END garbage
```
-- END code_block
"""
    path = _make_sql_file(content)
    try:
        queries = read_queries(path)
        assert len(queries) == 1
        assert queries[0]['sql'].strip() == 'SELECT 1;'
    finally:
        os.unlink(path)


def test_comment_before_sql_in_block():
    """块内 SQL 前的注释不应干扰解析。"""
    content = """-- 查询 Q1: test
-- this is a comment before SQL
SELECT * FROM t;
-- END Q1
"""
    path = _make_sql_file(content)
    try:
        queries = read_queries(path)
        assert len(queries) == 1
        assert queries[0]['name'] == '查询 Q1: test'
        assert 'SELECT * FROM t' in queries[0]['sql']
    finally:
        os.unlink(path)


def test_with_clause_cte():
    """WITH (CTE) 开头的查询也应被识别。"""
    content = """-- Q15: Top Supplier
WITH revenue AS (
    SELECT l_suppkey AS supplier_no,
           SUM(l_extendedprice) AS total_revenue
    FROM lineitem
    GROUP BY l_suppkey
)
SELECT s_suppkey, s_name, total_revenue
FROM supplier, revenue
WHERE s_suppkey = supplier_no;
-- END Q15
"""
    path = _make_sql_file(content)
    try:
        queries = read_queries(path)
        assert len(queries) == 1
        assert 'WITH' in queries[0]['sql'].upper()
        assert queries[0]['name'] == 'Q15: Top Supplier'
    finally:
        os.unlink(path)
