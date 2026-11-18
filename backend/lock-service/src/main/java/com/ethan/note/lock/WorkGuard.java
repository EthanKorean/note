package com.ethan.note.lock;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** 락 실험은 서로의 락과 시간을 망치므로 한 번에 하나만 실행하게 하는 공용 잠금. */
@Component
public class WorkGuard {

    private final AtomicReference<String> current = new AtomicReference<>();

    /** 작업을 시작한다. 이미 다른 작업이 있으면 BusyException. 끝나면 반드시 release() 를 부른다. */
    public void acquire(String what) {
        String prev = current.compareAndExchange(null, what);
        if (prev != null) {
            throw new BusyException("다른 작업이 실행 중입니다: " + prev); // 실패한 시점의 값을 그대로 쓴다(다시 읽으면 그 사이 null 이 될 수 있다)
        }
    }

    public void release() {
        current.set(null);
    }

    public String current() {
        return current.get();
    }
}
