-- =============================================================
-- TPC-C 测试查询（MySQL 方言）
-- 通过 SDTP 发送，SDTP 翻译为 PostgreSQL 方言执行
-- =============================================================
-- 数据规模：1 warehouse, 10 districts, 3000 customers/district
-- =============================================================

-- ==================== 事务 T1: New-Order ====================
-- 创建新订单（含订单详情）

-- T1-a: 查询仓库税率
SELECT w_tax FROM warehouse WHERE w_id = 1;

-- T1-b: 查询区域税率和下一订单号
SELECT d_tax, d_next_o_id FROM district WHERE d_w_id = 1 AND d_id = 1;

-- T1-c: 查询客户折扣率和信用
SELECT c_discount, c_last, c_credit FROM customer
WHERE c_w_id = 1 AND c_d_id = 1 AND c_id = 1;

-- T1-d: 查询商品价格和名称
SELECT i_price, i_name, i_data FROM item WHERE i_id = 1;
SELECT i_price, i_name, i_data FROM item WHERE i_id = 2;
SELECT i_price, i_name, i_data FROM item WHERE i_id = 3;
SELECT i_price, i_name, i_data FROM item WHERE i_id = 5;
SELECT i_price, i_name, i_data FROM item WHERE i_id = 10;

-- T1-e: 查询库存数量和分销信息
SELECT s_quantity, s_dist_01, s_ytd FROM stock
WHERE s_i_id = 1 AND s_w_id = 1;

-- T1-f: 更新库存（s_quantity 减订单数量，s_ytd 累加，s_order_cnt +1）
UPDATE stock SET s_quantity = s_quantity - 5, s_ytd = s_ytd + 5, s_order_cnt = s_order_cnt + 1
WHERE s_i_id = 1 AND s_w_id = 1;

-- T1-g: 更新区域下一订单号
UPDATE district SET d_next_o_id = d_next_o_id + 1 WHERE d_w_id = 1 AND d_id = 1;

-- T1-h: 插入订单（用 INSERT）
-- 使用 MySQL 的 NOW() 作为入口时间
INSERT INTO orders (o_id, o_d_id, o_w_id, o_c_id, o_entry_d, o_carrier_id, o_ol_cnt, o_all_local)
VALUES (3001, 1, 1, 1, NOW(), NULL, 5, 0);

-- T1-i: 插入新订单记录
INSERT INTO new_orders (no_o_id, no_d_id, no_w_id) VALUES (3001, 1, 1);

-- T1-j: 插入订单详情
INSERT INTO order_line (ol_o_id, ol_d_id, ol_w_id, ol_number, ol_i_id, ol_supply_w_id,
    ol_delivery_d, ol_quantity, ol_amount, ol_dist_info)
VALUES (3001, 1, 1, 1, 1, 1, NULL, 5, 45.00, 'dist_info_01');
INSERT INTO order_line (ol_o_id, ol_d_id, ol_w_id, ol_number, ol_i_id, ol_supply_w_id,
    ol_delivery_d, ol_quantity, ol_amount, ol_dist_info)
VALUES (3001, 1, 1, 2, 2, 1, NULL, 3, 22.50, 'dist_info_02');
INSERT INTO order_line (ol_o_id, ol_d_id, ol_w_id, ol_number, ol_i_id, ol_supply_w_id,
    ol_delivery_d, ol_quantity, ol_amount, ol_dist_info)
VALUES (3001, 1, 1, 3, 3, 1, NULL, 1, 15.00, 'dist_info_03');

-- END New-Order

-- ==================== 事务 T2: Payment ====================
-- 客户支付

-- T2-a: 按名称查询客户
SELECT c_id, c_first, c_middle, c_last, c_street_1, c_street_2, c_city, c_state, c_zip,
    c_phone, c_since, c_credit, c_credit_lim, c_discount, c_balance
FROM customer
WHERE c_w_id = 1 AND c_d_id = 1 AND c_last = 'BAR'
ORDER BY c_first
LIMIT 1;

-- T2-b: 查询仓库信息
SELECT w_name, w_street_1, w_street_2, w_city, w_state, w_zip
FROM warehouse WHERE w_id = 1;

-- T2-c: 查询区域信息
SELECT d_name, d_street_1, d_street_2, d_city, d_state, d_zip
FROM district WHERE d_w_id = 1 AND d_id = 1;

-- T2-d: 更新客户余额（信用良好）
UPDATE customer
SET c_balance = c_balance - 100.00,
    c_ytd_payment = c_ytd_payment + 100.00,
    c_payment_cnt = c_payment_cnt + 1
WHERE c_w_id = 1 AND c_d_id = 1 AND c_id = 1;

-- T2-e: 更新区域 YTD
UPDATE district SET d_ytd = d_ytd + 100.00 WHERE d_w_id = 1 AND d_id = 1;

-- T2-f: 更新仓库 YTD
UPDATE warehouse SET w_ytd = w_ytd + 100.00 WHERE w_id = 1;

-- T2-g: 插入历史记录
INSERT INTO history (h_c_id, h_c_d_id, h_c_w_id, h_d_id, h_w_id, h_date, h_amount, h_data)
VALUES (1, 1, 1, 1, 1, NOW(), 100.00, 'payment for order');

-- END Payment

-- ==================== 事务 T3: Order-Status ====================
-- 查询客户最近订单状态

-- T3-a: 按名称查询客户
SELECT c_id, c_first, c_middle, c_last, c_balance
FROM customer
WHERE c_w_id = 1 AND c_d_id = 1 AND c_last = 'BAR'
ORDER BY c_first;

-- T3-b: 查询最新订单
SELECT o_id, o_entry_d, o_carrier_id
FROM orders
WHERE o_w_id = 1 AND o_d_id = 1 AND o_c_id = 1
ORDER BY o_id DESC
LIMIT 1;

-- T3-c: 查询订单详情
SELECT ol_i_id, ol_supply_w_id, ol_quantity, ol_amount, ol_delivery_d
FROM order_line
WHERE ol_w_id = 1 AND ol_d_id = 1 AND ol_o_id = (
    SELECT MAX(o_id) FROM orders WHERE o_w_id = 1 AND o_d_id = 1 AND o_c_id = 1
)
ORDER BY ol_number;

-- END Order-Status

-- ==================== 事务 T4: Delivery ====================
-- 批量交货处理

-- T4-a: 查询新订单（各区域最旧的未发货订单）
SELECT no_o_id FROM new_orders
WHERE no_w_id = 1 AND no_d_id = 1
ORDER BY no_o_id ASC
LIMIT 1;

-- T4-b: 删除新订单记录
DELETE FROM new_orders
WHERE no_w_id = 1 AND no_d_id = 1 AND no_o_id = (
    SELECT MIN(no_o_id) FROM new_orders WHERE no_w_id = 1 AND no_d_id = 1
);

-- T4-c: 更新订单配送信息（使用 MySQL 日期函数）
UPDATE orders
SET o_carrier_id = 1
WHERE o_w_id = 1 AND o_d_id = 1 AND o_id = 1;

-- T4-d: 更新订单行配送时间
UPDATE order_line
SET ol_delivery_d = NOW()
WHERE ol_w_id = 1 AND ol_d_id = 1 AND ol_o_id = 1;

-- T4-e: 查询订单行总金额
SELECT SUM(ol_amount) AS total_amount
FROM order_line
WHERE ol_w_id = 1 AND ol_d_id = 1 AND ol_o_id = 1;

-- T4-f: 更新客户余额
UPDATE customer
SET c_balance = c_balance + (
    SELECT COALESCE(SUM(ol_amount), 0)
    FROM order_line
    WHERE ol_w_id = 1 AND ol_d_id = 1 AND ol_o_id = 1
),
    c_delivery_cnt = c_delivery_cnt + 1
WHERE c_w_id = 1 AND c_d_id = 1 AND c_id = (
    SELECT o_c_id FROM orders WHERE o_w_id = 1 AND o_d_id = 1 AND o_id = 1
);

-- END Delivery

-- ==================== 事务 T5: Stock-Level ====================
-- 查询库存水平

-- T5-a: 查询区域下一可用订单号
SELECT d_next_o_id FROM district WHERE d_w_id = 1 AND d_id = 1;

-- T5-b: 统计最近 20 个订单中的低库存商品数量（库存 < 10）
SELECT COUNT(DISTINCT ol_i_id) AS low_stock_count
FROM order_line, stock
WHERE ol_w_id = 1
    AND ol_d_id = 1
    AND ol_o_id BETWEEN (
        SELECT d_next_o_id - 20 FROM district WHERE d_w_id = 1 AND d_id = 1
    ) AND (
        SELECT d_next_o_id - 1 FROM district WHERE d_w_id = 1 AND d_id = 1
    )
    AND s_i_id = ol_i_id
    AND s_w_id = 1
    AND s_quantity < 10;

-- END Stock-Level

-- ==================== 额外分析查询 ====================

-- 查询各区域订单量统计
SELECT d_id, COUNT(*) AS order_count, COUNT(DISTINCT o_c_id) AS unique_customers
FROM orders, district
WHERE o_w_id = 1 AND d_w_id = 1 AND d_id = o_d_id
GROUP BY d_id
ORDER BY d_id;

-- 查询商品销售排行
SELECT i_id, i_name, SUM(ol_quantity) AS total_qty, AVG(ol_amount) AS avg_amount
FROM item, order_line
WHERE i_id = ol_i_id AND ol_w_id = 1
GROUP BY i_id, i_name
ORDER BY total_qty DESC
LIMIT 10;

-- 查询客户消费排行
SELECT c_id, c_last, c_first, c_balance, c_ytd_payment
FROM customer
WHERE c_w_id = 1
ORDER BY c_ytd_payment DESC
LIMIT 10;

-- 查询 District 统计
SELECT d_id, d_name, d_ytd, d_next_o_id
FROM district
WHERE d_w_id = 1
ORDER BY d_id;
