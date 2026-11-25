-- 使用方示例测试脚本（H2）：MetaMapperXmlUsingTest 用的 demo_order 表
CREATE TABLE IF NOT EXISTS demo_order (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id   BIGINT,
    order_no  VARCHAR(64) NOT NULL,
    amount    DECIMAL(12, 2)
);
