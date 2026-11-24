-- 18 작업: 트랜잭션의 종류(http.server, pageload, navigation)와 끝에서 남긴 부분 추적(8.4)을 구별한다.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS op LowCardinality(String) DEFAULT 'http.server' AFTER name;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS partial UInt8 DEFAULT 0 AFTER status;
