package com.ethan.note.image;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** 실험·이미지 생성·캐시 비우기는 서로 결과를 망치므로 한 번에 하나만 실행하게 하는 공용 잠금. */
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

    public boolean isBusy() {
        return current.get() != null;
    }

    public String current() {
        return current.get();
    }
}
