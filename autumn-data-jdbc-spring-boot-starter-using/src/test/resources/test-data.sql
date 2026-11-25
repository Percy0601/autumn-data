-- 使用方示例测试数据（H2）：demo_order 三条种子（SO-1001~1003）
-- 先删后插：H2 内存库在同一次 mvn 构建里可能被多个上下文重复初始化，脚本需可重复执行
DELETE FROM demo_order;

INSERT INTO demo_order (id, user_id, order_no, amount) VALUES (1, 1, 'SO-1001', 199.00);
INSERT INTO demo_order (id, user_id, order_no, amount) VALUES (2, 1, 'SO-1002', 58.50);
INSERT INTO demo_order (id, user_id, order_no, amount) VALUES (3, 2, 'SO-1003', 1200.00);

-- 固定主键已占用 1~3，自增序列从 100 起，避免生成键与种子数据冲突
ALTER TABLE demo_order ALTER COLUMN id RESTART WITH 100;
