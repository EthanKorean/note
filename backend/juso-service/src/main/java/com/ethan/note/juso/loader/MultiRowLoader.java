package com.ethan.note.juso.loader;

import com.ethan.note.juso.JusoColumns;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/** INSERT ... VALUES (..),(..),(..) 로 한 문장에 여러 행을 넣는 도우미. 병렬 방식(ParallelLoader)이 쓴다. */
public final class MultiRowLoader {

    /** 바인드 변수 상한(65,535) ÷ 컬럼 수(23) */
    public static final int MAX_ROWS_PER_STATEMENT = 65_535 / JusoColumns.COUNT;

    private MultiRowLoader() {
    }

    static void checkBatchSize(int batchSize) {
        if (batchSize > MAX_ROWS_PER_STATEMENT) {
            throw new IllegalArgumentException(
                    "멀티 로우 INSERT는 batchSize를 " + MAX_ROWS_PER_STATEMENT + " 이하로 (바인드 변수 65,535개 제한)");
        }
    }

    static void insertChunk(Connection conn, String table, List<String[]> chunk) throws SQLException {
        String sql = "INSERT INTO " + table + " (" + JusoColumns.COLUMN_LIST + ") VALUES "
                + String.join(",", Collections.nCopies(chunk.size(), JusoColumns.ROW_PLACEHOLDER));
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int offset = 1;
            for (String[] row : chunk) {
                JusoColumns.bind(ps, offset, row);
                offset += JusoColumns.COUNT;
            }
            ps.executeUpdate();
        }
    }
}
