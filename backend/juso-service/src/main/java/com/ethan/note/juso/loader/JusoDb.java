package com.ethan.note.juso.loader;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** 커넥션을 빌려주고, 필요하면 세션 단위로 검사를 꺼 둔다(반납 전에 복구). */
@Component
public class JusoDb {

    @FunctionalInterface
    public interface ConnWork {
        void run(Connection conn) throws Exception;
    }

    private final DataSource dataSource;

    public JusoDb(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void withConnection(boolean relaxChecks, ConnWork work) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try {
                if (relaxChecks) {
                    exec(conn, "SET unique_checks=0, foreign_key_checks=0");
                }
                work.run(conn);
            } finally {
                if (relaxChecks) {
                    try {
                        exec(conn, "SET unique_checks=1, foreign_key_checks=1");
                    } catch (SQLException ignored) {
                        // 풀 커넥션이 이미 깨졌다면 폐기되므로 무시
                    }
                }
            }
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
