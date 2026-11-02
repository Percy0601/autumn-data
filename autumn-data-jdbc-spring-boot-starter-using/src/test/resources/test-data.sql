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

-- ===========================================================================
-- 图书馆演示数据：3 作者 / 6 本书 / 4 分类 / 9 条中间表记录
-- 先删后插（子表在前），保证脚本在同一次构建里重复执行结果一致
-- ===========================================================================
DELETE FROM lib_book_category;
DELETE FROM lib_book;
DELETE FROM lib_category;
DELETE FROM lib_author;

INSERT INTO lib_author (id, name, email, age, rating, book_count, fans_count, active, royalty, birthday)
VALUES (1, '鲁迅', 'luxun@example.com', 55, 5, 3, 100000, TRUE, 128000.00, DATE '1881-09-25');
INSERT INTO lib_author (id, name, email, age, rating, book_count, fans_count, active, royalty, birthday)
VALUES (2, '金庸', 'jinyong@example.com', 94, 5, 2, 500000, TRUE, 990000.00, DATE '1924-03-10');
-- 无名氏：email / rating / fans_count 均为 NULL，供 IsNull / IsEmpty 类关键词使用
INSERT INTO lib_author (id, name, email, age, rating, book_count, fans_count, active, royalty, birthday)
VALUES (3, '无名氏', NULL, 30, NULL, 1, NULL, FALSE, 0.00, NULL);

INSERT INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count, review_count, published, published_at)
VALUES (1, 1, '呐喊', 'ISBN-1001', 39.90, 320, 100, 12000, 3000, TRUE, TIMESTAMP '1923-01-01 00:00:00');
INSERT INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count, review_count, published, published_at)
VALUES (2, 1, '彷徨', 'ISBN-1002', 42.00, 280, 50, 8000, 1500, TRUE, TIMESTAMP '1926-01-01 00:00:00');
INSERT INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count, review_count, published, published_at)
VALUES (3, 2, '射雕英雄传', 'ISBN-2001', 88.00, 1200, 200, 500000, 120000, TRUE, TIMESTAMP '1957-01-01 00:00:00');
-- 神雕侠侣：stock 为 NULL（Integer 可空）
INSERT INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count, review_count, published, published_at)
VALUES (4, 2, '神雕侠侣', 'ISBN-2002', 92.50, 1100, NULL, 400000, 98000, TRUE, TIMESTAMP '1959-01-01 00:00:00');
-- 无名书稿：review_count / published_at 为 NULL
INSERT INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count, review_count, published, published_at)
VALUES (5, 3, '无名书稿', 'ISBN-3001', 9.90, 100, 0, 10, NULL, FALSE, NULL);
INSERT INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count, review_count, published, published_at)
VALUES (6, 1, '绝版孤本', 'ISBN-1003', 199.00, 500, 5, 100, 20, FALSE, TIMESTAMP '1930-01-01 00:00:00');

INSERT INTO lib_category (id, name, code, sort_order, depth, enabled) VALUES (1, '文学', 'LIT', 1, 1, TRUE);
INSERT INTO lib_category (id, name, code, sort_order, depth, enabled) VALUES (2, '武侠', 'WUXIA', 2, 1, TRUE);
INSERT INTO lib_category (id, name, code, sort_order, depth, enabled) VALUES (3, '现代文学', 'MODERN', 3, 2, TRUE);
INSERT INTO lib_category (id, name, code, sort_order, depth, enabled) VALUES (4, '绝版', 'RARE', 4, NULL, FALSE);

-- n:1:n 中间表：呐喊/彷徨→文学+现代文学，射雕/神雕→武侠，无名书稿→现代文学，绝版孤本→文学+绝版
INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (1, 1, 1, 10);
INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (2, 1, 3, 5);
INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (3, 2, 1, 8);
INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (4, 2, 3, 6);
INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (5, 3, 2, 10);
INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (6, 4, 2, 9);
INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (7, 5, 3, 1);
INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (8, 6, 1, 2);
INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (9, 6, 4, 10);

ALTER TABLE lib_author ALTER COLUMN id RESTART WITH 100;
ALTER TABLE lib_book ALTER COLUMN id RESTART WITH 100;
ALTER TABLE lib_category ALTER COLUMN id RESTART WITH 100;
ALTER TABLE lib_book_category ALTER COLUMN id RESTART WITH 100;
