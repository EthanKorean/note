-- 락 전략 실험용 쇼핑몰 테이블. lock-service 가 시작할 때 자동으로 만든다(IF NOT EXISTS 라 다시 실행해도 안전하다).
-- 실험을 돌릴 때마다 lock_product 의 재고와 주문은 서버가 처음 상태로 되돌린다.
CREATE TABLE IF NOT EXISTS lock_product (
    id      INT          NOT NULL COMMENT '상품 번호',
    name    VARCHAR(50)  NOT NULL COMMENT '상품 이름',
    stock   INT          NOT NULL COMMENT '재고',
    version INT          NOT NULL DEFAULT 0 COMMENT '낙관적 락용 버전(수정할 때마다 1 증가)',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='락 실험: 상품과 재고';

CREATE TABLE IF NOT EXISTS lock_order (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    buyer      INT          NOT NULL COMMENT '주문한 사람 번호',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='락 실험: 주문';

CREATE TABLE IF NOT EXISTS lock_order_item (
    order_id   BIGINT NOT NULL,
    product_id INT    NOT NULL,
    qty        INT    NOT NULL,
    PRIMARY KEY (order_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='락 실험: 주문 상품';
