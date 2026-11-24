-- 추적 확인용(18 작업). 주문마다 상품 12개.
CREATE TABLE IF NOT EXISTS item (id INT PRIMARY KEY, order_id INT, name VARCHAR(40));
MERGE INTO item KEY (id) SELECT x, MOD(x, 5), CONCAT('상품 ', x) FROM SYSTEM_RANGE(1, 60);
