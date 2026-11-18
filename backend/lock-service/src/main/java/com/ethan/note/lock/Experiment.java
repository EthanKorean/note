package com.ethan.note.lock;

import java.util.List;

/** 실험 목록. 새 실험을 더할 때 고칠 곳: 여기(이름, 그룹, 기본 주문 수), LockLab.attempt() 의 switch, 화면의 frontend/src/lockScenarios.ts(experiments). */
public enum Experiment {
    STOCK_NONE("stock-none", "락 없이 읽고 쓰기", "stock", 100, List.of(1)),
    STOCK_ATOMIC("stock-atomic", "원자적 UPDATE", "stock", 100, List.of(1)),
    STOCK_PESSIMISTIC("stock-pessimistic", "비관적 락(SELECT FOR UPDATE)", "stock", 100, List.of(1)),
    STOCK_OPTIMISTIC("stock-optimistic", "낙관적 락(버전 + 재시도)", "stock", 100, List.of(1)),
    DEADLOCK_CROSS("deadlock-cross", "서로 다른 순서로 잠그기", "deadlock", 20, List.of(1, 2)),
    DEADLOCK_ORDERED("deadlock-ordered", "상품 번호 순서로 잠그기", "deadlock", 20, List.of(1, 2)),
    DEADLOCK_RETRY("deadlock-retry", "데드락이 나면 다시 시도", "deadlock", 20, List.of(1, 2)),
    TX_LONG("tx-long", "결제를 트랜잭션 안에서", "tx", 50, List.of(1)),
    TX_SHORT("tx-short", "결제를 트랜잭션 밖에서", "tx", 50, List.of(1));

    private final String id;
    private final String title;
    private final String group;
    private final int defaultBuyers;
    private final List<Integer> products;

    Experiment(String id, String title, String group, int defaultBuyers, List<Integer> products) {
        this.id = id;
        this.title = title;
        this.group = group;
        this.defaultBuyers = defaultBuyers;
        this.products = products;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    /** stock(재고 차감 방식) | deadlock(여러 상품을 잠그는 순서) | tx(트랜잭션 길이) */
    public String group() {
        return group;
    }

    public int defaultBuyers() {
        return defaultBuyers;
    }

    /** 이 실험이 건드리는 상품 번호. */
    public List<Integer> products() {
        return products;
    }

    public static Experiment of(String id) {
        for (Experiment e : values()) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        throw new BadRequestException("알 수 없는 실험입니다: " + id);
    }
}
