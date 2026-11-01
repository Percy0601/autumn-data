-- 使用方示例初始化脚本（H2）：basic_user 一对多 demo_order
CREATE TABLE IF NOT EXISTS basic_user (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    username  VARCHAR(64) NOT NULL,
    email     VARCHAR(128)
);

CREATE TABLE IF NOT EXISTS demo_order (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id   BIGINT,
    order_no  VARCHAR(64) NOT NULL,
    amount    DECIMAL(12, 2)
);
