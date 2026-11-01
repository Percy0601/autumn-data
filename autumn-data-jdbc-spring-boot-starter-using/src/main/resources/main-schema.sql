-- Web 演示用表结构与种子数据（H2 MODE=MySQL，可为持久化 TCP 库，脚本可重复执行）
CREATE TABLE IF NOT EXISTS demo_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    age         INT          NOT NULL,
    email       VARCHAR(128),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 固定主键 + INSERT IGNORE：重启应用不会产生重复行
INSERT IGNORE INTO demo_user (id, name, age, email) VALUES (1, 'alice', 30, 'alice@example.com');
INSERT IGNORE INTO demo_user (id, name, age, email) VALUES (2, 'bob', 25, 'bob@example.com');
