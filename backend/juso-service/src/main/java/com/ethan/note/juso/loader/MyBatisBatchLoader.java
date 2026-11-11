package com.ethan.note.juso.loader;

import com.ethan.note.juso.mybatis.JusoMapper;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MyBatis ExecutorType.BATCH: 행마다 매퍼를 호출하고 N행마다 트랜잭션을 커밋(커밋 직전에 배치 전송). */
@Component
public class MyBatisBatchLoader implements JusoLoader {

    private final SqlSessionFactory factory;
    private final TransactionTemplate tx;

    public MyBatisBatchLoader(SqlSessionFactory factory, PlatformTransactionManager txManager) {
        this.factory = factory;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public String name() {
        return "mybatis-batch";
    }

    @Override
    public void load(LoadContext ctx) throws Exception {
        SqlSessionTemplate batch = new SqlSessionTemplate(factory, ExecutorType.BATCH);
        JusoMapper mapper = batch.getMapper(JusoMapper.class);
        Rows.forEachChunk(ctx.file(), ctx.batchSize(), chunk -> tx.executeWithoutResult(s -> {
            for (String[] row : chunk) {
                mapper.insertOne(ctx.table(), row);
            }
        }));
    }
}
