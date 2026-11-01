-- Web 演示脚本：demo profile 连的是本地 H2 TCP 服务上的 autumn_security 库，
-- 其中 basic_user 是安全项目已有的真实表（17 列，email 等有 NOT NULL 默认值约束）。
-- 因此这里只建示例自有表 demo_order，绝不 ALTER / 不往别人的 basic_user 插数据；
-- 父侧读的是那库里已有的用户，子侧行由下面的 INSERT IGNORE 固定主键补齐（重启不重复插）。
-- 需要自有 basic_user 的场景走测试内存库（src/test/resources/test-schema.sql）。
CREATE TABLE IF NOT EXISTS demo_order (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id   BIGINT,
    order_no  VARCHAR(64) NOT NULL,
    amount    DECIMAL(12, 2)
);

-- 外键指向 autumn_security.basic_user 里已存在的用户 id
INSERT IGNORE INTO demo_order (id, user_id, order_no, amount) VALUES (1, 1, 'SO-1001', 199.00);
INSERT IGNORE INTO demo_order (id, user_id, order_no, amount) VALUES (2, 1, 'SO-1002', 58.50);
INSERT IGNORE INTO demo_order (id, user_id, order_no, amount) VALUES (3, 2, 'SO-1003', 1200.00);
