-- TPC-C T4: Delivery 事务

-- T4-a
SELECT no_o_id FROM new_orders
WHERE no_w_id = 1 AND no_d_id = 1
ORDER BY no_o_id ASC
LIMIT 1;

-- T4-b
-- 注意：MySQL 不允许在 DELETE/UPDATE 的子查询中引用同一张目标表（Error 1093）。
-- 通过将 MIN 子查询再包一层派生表，强制 MySQL 物化子查询结果，规避该限制。
-- 语义与标准 TPC-C Delivery 一致：删除指定 (w_id,d_id) 下 order id 最小的那条 new_order。
DELETE FROM new_orders
WHERE no_w_id = 1 AND no_d_id = 1 AND no_o_id = (
    SELECT no_o_id FROM (
        SELECT MIN(no_o_id) AS no_o_id FROM new_orders WHERE no_w_id = 1 AND no_d_id = 1
    ) AS t_min
);

-- T4-c
UPDATE orders
SET o_carrier_id = 1
WHERE o_w_id = 1 AND o_d_id = 1 AND o_id = 1;

-- T4-d
UPDATE order_line
SET ol_delivery_d = CURRENT_TIMESTAMP
WHERE ol_w_id = 1 AND ol_d_id = 1 AND ol_o_id = 1;

-- T4-e
SELECT SUM(ol_amount) AS total_amount
FROM order_line
WHERE ol_w_id = 1 AND ol_d_id = 1 AND ol_o_id = 1;

-- T4-f
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