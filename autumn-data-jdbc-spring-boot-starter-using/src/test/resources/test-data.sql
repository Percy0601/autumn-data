-- 使用方示例数据（H2）：3 个用户，alice 2 单 / bob 1 单 / carol 无单
-- 先删后插：H2 内存库在同一次 mvn 构建里可能被多个上下文重复初始化，脚本需可重复执行
DELETE FROM demo_order;
DELETE FROM basic_user;

INSERT INTO basic_user (id, username, email) VALUES (1, 'alice', 'alice@example.com');
INSERT INTO basic_user (id, username, email) VALUES (2, 'bob', 'bob@example.com');
INSERT INTO basic_user (id, username, email) VALUES (3, 'carol', NULL);

INSERT INTO demo_order (id, user_id, order_no, amount) VALUES (1, 1, 'SO-1001', 199.00);
INSERT INTO demo_order (id, user_id, order_no, amount) VALUES (2, 1, 'SO-1002', 58.50);
INSERT INTO demo_order (id, user_id, order_no, amount) VALUES (3, 2, 'SO-1003', 1200.00);

-- 固定主键已占用 1~3，自增序列从 100 起，避免生成键与种子数据冲突
ALTER TABLE basic_user ALTER COLUMN id RESTART WITH 100;
ALTER TABLE demo_order ALTER COLUMN id RESTART WITH 100;
