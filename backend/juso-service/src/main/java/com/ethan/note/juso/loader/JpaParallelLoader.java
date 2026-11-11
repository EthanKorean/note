package com.ethan.note.juso.loader;

import com.ethan.note.juso.jpa.RnaddrKor;
import com.ethan.note.juso.jpa.RnaddrKorRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring Data JPA saveAll 을 워커 N개가 각자 트랜잭션(=각자 EntityManager/커넥션)으로 실행한다.
 * 행마다 SELECT 하는 merge 비용은 그대로지만 DB 왕복이 겹쳐서 빨라지는지 보는 방식이다.
 */
@Component
public class JpaParallelLoader implements JusoLoader {

    private final RnaddrKorRepository repository;
    private final TransactionTemplate tx;

    public JpaParallelLoader(RnaddrKorRepository repository, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public String name() {
        return "jpa-parallel";
    }

    @Override
    public void validate(LoadContext ctx) {
        if (!"rnaddr_kor".equals(ctx.table())) {
            throw new IllegalArgumentException("jpa-parallel 방식은 InnoDB 테이블(rnaddr_kor)만 지원합니다.");
        }
    }

    @Override
    public void load(LoadContext ctx) throws Exception {
        ParallelRunner.run(ctx, source -> {
            List<String[]> chunk;
            while ((chunk = source.next()) != null) {
                List<String[]> rows = chunk;
                tx.executeWithoutResult(s -> repository.saveAll(rows.stream().map(RnaddrKor::from).toList()));
            }
        });
    }
}
