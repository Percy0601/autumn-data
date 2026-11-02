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

-- ---------------------------------------------------------------------------
-- 图书馆演示（lib_*）：1:n（author→book）、n:1（book→author）、
-- n:1:n 多对多（book→book_category→category）+ 全量字段类型
-- ---------------------------------------------------------------------------
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
