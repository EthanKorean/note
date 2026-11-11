package com.ethan.note.juso.loader;

import java.util.List;
import org.springframework.stereotype.Component;

/** 생산자 1개 + 워커 N개(각자 JDBC 커넥션으로 멀티 로우 INSERT). 공통 골격은 {@link ParallelRunner}. */
@Component
public class ParallelLoader implements JusoLoader {

    private final JusoDb db;

    public ParallelLoader(JusoDb db) {
        this.db = db;
    }

    @Override
    public String name() {
        return "parallel";
    }

    @Override
    public void validate(LoadContext ctx) {
        MultiRowLoader.checkBatchSize(ctx.batchSize());
    }

    @Override
    public void load(LoadContext ctx) throws Exception {
        ParallelRunner.run(ctx, source -> db.withConnection(ctx.relaxChecks(), conn -> {
            conn.setAutoCommit(false);
            List<String[]> chunk;
            while ((chunk = source.next()) != null) {
                MultiRowLoader.insertChunk(conn, ctx.table(), chunk);
                conn.commit();
            }
        }));
    }
}
