-- ============================================================
-- 一次性示例数据：MySQL / PostgreSQL 都能执行（纯 INSERT，无方言语法）。
--
-- 前提是刚建完库（自增主键从 1 开始）；重复执行会撞唯一键，只在首次初始化时跑。
-- ============================================================

INSERT INTO demo_customer (customer_no, name, phone, level) VALUES
('C0001', '张三', '13800000000', 1),
('C0002', '李四', '13900000000', 2);

INSERT INTO demo_order (order_no, customer_id, amount, status, remark) VALUES
('SO-20261113-001', 1, 128.00, 1, '首单'),
('SO-20261113-002', 1, 76.50, 0, NULL),
('SO-20261113-003', 2, 999.00, 2, '加急');

INSERT INTO demo_order_item (order_id, sku, product_name, quantity, price) VALUES
(1, 'SKU-A-01', '苹果', 2, 64.00),
(2, 'SKU-B-02', '梨子', 3, 25.50),
(3, 'SKU-C-03', '整箱橙子', 1, 999.00);
