package com.ethan.note.juso.loader;

import com.ethan.note.juso.mybatis.JusoMapper;
import java.util.List;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 워커 N개가 각자 트랜잭션(=각자 커넥션)으로 행마다 insertOne 을 쌓고 청크마다 커밋한다(BATCH 실행기).
 * SqlSessionTemplate 은 스레드 안전하며, 트랜잭션마다 스레드 전용 SqlSession 을 꺼내 쓴다.
 */
@Component
public class MyBatisParallelBatchLoader implements JusoLoader {

    private final SqlSessionFactory factory;
    private final TransactionTemplate tx;

    public MyBatisParallelBatchLoader(SqlSessionFactory factory, PlatformTransactionManager txManager) {
        this.factory = factory;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public String name() {
        return "mybatis-parallel-batch";
    }

    @Override
    public void load(LoadContext ctx) throws Exception {
        JusoMapper mapper = new SqlSessionTemplate(factory, ExecutorType.BATCH).getMapper(JusoMapper.class);
        ParallelRunner.run(ctx, source -> {
            List<String[]> chunk;
            while ((chunk = source.next()) != null) {
                List<String[]> rows = chunk;
                tx.executeWithoutResult(s -> {
                    for (String[] row : rows) {
                        mapper.insertOne(ctx.table(), row);
                    }
                });
            }
        });
    }
}
