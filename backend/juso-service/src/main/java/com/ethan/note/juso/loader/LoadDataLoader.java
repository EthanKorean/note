package com.ethan.note.juso.loader;

import com.ethan.note.juso.JusoColumns;
import java.sql.Statement;

/** LOAD DATA 공통 로직. LOCAL이면 앱이 파일을 읽어 전송하고, 아니면 DB 서버가 직접 읽는다. */
abstract class LoadDataLoader implements JusoLoader {

    private final JusoDb db;
    private final boolean local;

    LoadDataLoader(JusoDb db, boolean local) {
        this.db = db;
        this.local = local;
    }

    @Override
    public void load(LoadContext ctx) throws Exception {
        String path = local ? ctx.file().toString() : ctx.serverPath();
        String sql = "LOAD DATA " + (local ? "LOCAL " : "") + "INFILE '" + escape(path) + "'"
                + " INTO TABLE " + ctx.table()
                // MariaDB에는 cp949가 없다. 202610 데이터에는 확장 한글(똠, 샾 등) 175행이 있으나 MariaDB euckr 적재 결과가 Java MS949와 같음을 확인했다.
                // 확장 한글(UHC)이 섞이는 다른 기준월은 UTF-8로 변환해 올리거나 JDBC 방식을 쓸 것.
                + " CHARACTER SET euckr"
                + " FIELDS TERMINATED BY '|' ESCAPED BY ''"
                + " LINES TERMINATED BY '\\n'"
                + " (" + JusoColumns.loadDataVars() + ")"
                + " SET " + JusoColumns.loadDataSet();
        db.withConnection(ctx.relaxChecks(), conn -> {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(sql);
            }
        });
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("'", "''");
    }
}
