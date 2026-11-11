package com.ethan.note.juso.loader;

import com.ethan.note.juso.JusoColumns;
import com.ethan.note.juso.mybatis.JusoMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 워커 N개가 각자 트랜잭션(=각자 커넥션)으로 &lt;foreach&gt; 멀티 로우 INSERT 를 실행하고 청크마다 커밋한다. */
@Component
public class MyBatisParallelForeachLoader implements JusoLoader {

    private final JusoMapper mapper;
    private final TransactionTemplate tx;

    public MyBatisParallelForeachLoader(JusoMapper mapper, PlatformTransactionManager txManager) {
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public String name() {
        return "mybatis-parallel-foreach";
    }

    @Override
    public void validate(LoadContext ctx) {
        MultiRowLoader.checkBatchSize(ctx.batchSize());
    }

    @Override
    public void load(LoadContext ctx) throws Exception {
        ParallelRunner.run(ctx, source -> {
            List<String[]> chunk;
            while ((chunk = source.next()) != null) {
                List<String[]> rows = chunk;
                tx.executeWithoutResult(s -> mapper.insertMulti(ctx.table(), toMaps(rows)));
            }
        });
    }

    private static List<Map<String, String>> toMaps(List<String[]> chunk) {
        List<Map<String, String>> rows = new ArrayList<>(chunk.size());
        for (String[] row : chunk) {
            Map<String, String> m = new HashMap<>(32);
            for (int i = 0; i < JusoColumns.COUNT; i++) {
                m.put(JusoColumns.NAMES.get(i), row[i]);
            }
            rows.add(m);
        }
        return rows;
    }
}
