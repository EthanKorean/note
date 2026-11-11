package com.ethan.note.juso;

import java.util.Map;

/** 엔진 키(innodb) ↔ 테이블 이름. 사용자 입력이 SQL에 들어가지 않도록 화이트리스트로만 변환한다. */
public final class JusoTables {

    public static final Map<String, String> BY_ENGINE = Map.of("innodb", "rnaddr_kor");

    private JusoTables() {
    }

    public static String resolve(String engine) {
        String table = BY_ENGINE.get(engine == null ? "" : engine.toLowerCase(java.util.Locale.ROOT));
        if (table == null) {
            throw new IllegalArgumentException("지원하지 않는 엔진: " + engine + " (innodb)");
        }
        return table;
    }

    /** 01-schema.sql 의 보조 인덱스와 동일해야 한다. */
    public static String dropIndexesSql(String table) {
        return "ALTER TABLE " + table + " DROP INDEX IF EXISTS idx_road, DROP INDEX IF EXISTS idx_zip";
    }

    public static String addIndexesSql(String table) {
        return "ALTER TABLE " + table
                + " ADD INDEX IF NOT EXISTS idx_road (road_code, bld_main, bld_sub), ADD INDEX IF NOT EXISTS idx_zip (zip_code)";
    }
}
