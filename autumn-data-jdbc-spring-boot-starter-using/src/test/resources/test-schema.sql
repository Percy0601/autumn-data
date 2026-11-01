-- 使用方示例初始化脚本（H2）
CREATE TABLE IF NOT EXISTS demo_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    age         INT          NOT NULL,
    email       VARCHAR(128),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
