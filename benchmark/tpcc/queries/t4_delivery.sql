-- TPC-C T4: Delivery 事务

-- T4-a
SELECT no_o_id FROM new_orders
WHERE no_w_id = 1 AND no_d_id = 1
ORDER BY no_o_id ASC
LIMIT 1;

-- T4-b
DELETE FROM new_orders
WHERE no_w_id = 1 AND no_d_id = 1 AND no_o_id = (
    SELECT MIN(no_o_id) FROM new_orders WHERE no_w_id = 1 AND no_d_id = 1
);

-- T4-c
UPDATE orders
SET o_carrier_id = 1
WHERE o_w_id = 1 AND o_d_id = 1 AND o_id = 1;

-- T4-d
UPDATE order_line
SET ol_delivery_d = NOW()
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