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

-- ============================================================================
-- 图书馆演示（lib_*）：覆盖 1:n / n:1 / n:1:n 三种关联 + 全量字段类型
--   lib_author   1 ── n  lib_book           （一对多）
--   lib_book     n ── 1  lib_author         （多对一）
--   lib_book     1 ── n  lib_book_category  ── n ── 1  lib_category  （n:1:n 多对多）
-- 字段类型覆盖：int / Integer / long / Long / String / Boolean / BigDecimal / Date
-- 重启不重复插，故用固定主键 + INSERT IGNORE。
-- ============================================================================
CREATE TABLE IF NOT EXISTS lib_author (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64) NOT NULL,
    email       VARCHAR(128),
    age         INT,
    rating      INT,
    book_count  BIGINT,
    fans_count  BIGINT,
    active      BOOLEAN,
    royalty     DECIMAL(12, 2),
    birthday    DATE
);

CREATE TABLE IF NOT EXISTS lib_book (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    author_id     BIGINT,
    title         VARCHAR(128) NOT NULL,
    isbn          VARCHAR(32),
    price         DECIMAL(12, 2),
    pages         INT,
    stock         INT,
    sold_count    BIGINT,
    review_count  BIGINT,
    published     BOOLEAN,
    published_at  TIMESTAMP
);

CREATE TABLE IF NOT EXISTS lib_category (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64) NOT NULL,
    code        VARCHAR(32),
    sort_order  INT,
    depth       INT,
    enabled     BOOLEAN
);

CREATE TABLE IF NOT EXISTS lib_book_category (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    book_id      BIGINT,
    category_id  BIGINT,
    weight       INT
);

INSERT IGNORE INTO lib_author (id, name, email, age, rating, book_count, fans_count, active, royalty, birthday)
VALUES (1, '鲁迅', 'luxun@example.com', 55, 5, 3, 100000, TRUE, 128000.00, DATE '1881-09-25');
INSERT IGNORE INTO lib_author (id, name, email, age, rating, book_count, fans_count, active, royalty, birthday)
VALUES (2, '金庸', 'jinyong@example.com', 94, 5, 2, 500000, TRUE, 990000.00, DATE '1924-03-10');
INSERT IGNORE INTO lib_author (id, name, email, age, rating, book_count, fans_count, active, royalty, birthday)
VALUES (3, '无名氏', NULL, 30, NULL, 1, NULL, FALSE, 0.00, NULL);

INSERT IGNORE INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count, review_count, published, published_at)
VALUES (1, 1, '呐喊', 'ISBN-1001', 39.90, 320, 100, 12000, 3000, TRUE, TIMESTAMP '1923-01-01 00:00:00');
INSERT IGNORE INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count, review_count, published, published_at)
VALUES (2, 1, '彷徨', 'ISBN-1002', 42.00, 280, 50, 8000, 1500, TRUE, TIMESTAMP '1926-01-01 00:00:00');
INSERT IGNORE INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count, review_count, published, published_at)
VALUES (3, 2, '射雕英雄传', 'ISBN-2001', 88.00, 1200, 200, 500000, 120000, TRUE, TIMESTAMP '1957-01-01 00:00:00');
INSERT IGNORE INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count, review_count, published, published_at)
VALUES (4, 2, '神雕侠侣', 'ISBN-2002', 92.50, 1100, NULL, 400000, 98000, TRUE, TIMESTAMP '1959-01-01 00:00:00');
INSERT IGNORE INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count, review_count, published, published_at)
VALUES (5, 3, '无名书稿', 'ISBN-3001', 9.90, 100, 0, 10, NULL, FALSE, NULL);
INSERT IGNORE INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count, review_count, published, published_at)
VALUES (6, 1, '绝版孤本', 'ISBN-1003', 199.00, 500, 5, 100, 20, FALSE, TIMESTAMP '1930-01-01 00:00:00');

INSERT IGNORE INTO lib_category (id, name, code, sort_order, depth, enabled) VALUES (1, '文学', 'LIT', 1, 1, TRUE);
INSERT IGNORE INTO lib_category (id, name, code, sort_order, depth, enabled) VALUES (2, '武侠', 'WUXIA', 2, 1, TRUE);
INSERT IGNORE INTO lib_category (id, name, code, sort_order, depth, enabled) VALUES (3, '现代文学', 'MODERN', 3, 2, TRUE);
INSERT IGNORE INTO lib_category (id, name, code, sort_order, depth, enabled) VALUES (4, '绝版', 'RARE', 4, NULL, FALSE);

INSERT IGNORE INTO lib_book_category (id, book_id, category_id, weight) VALUES (1, 1, 1, 10);
INSERT IGNORE INTO lib_book_category (id, book_id, category_id, weight) VALUES (2, 1, 3, 5);
INSERT IGNORE INTO lib_book_category (id, book_id, category_id, weight) VALUES (3, 2, 1, 8);
INSERT IGNORE INTO lib_book_category (id, book_id, category_id, weight) VALUES (4, 2, 3, 6);
INSERT IGNORE INTO lib_book_category (id, book_id, category_id, weight) VALUES (5, 3, 2, 10);
INSERT IGNORE INTO lib_book_category (id, book_id, category_id, weight) VALUES (6, 4, 2, 9);
INSERT IGNORE INTO lib_book_category (id, book_id, category_id, weight) VALUES (7, 5, 3, 1);
INSERT IGNORE INTO lib_book_category (id, book_id, category_id, weight) VALUES (8, 6, 1, 2);
INSERT IGNORE INTO lib_book_category (id, book_id, category_id, weight) VALUES (9, 6, 4, 10);
