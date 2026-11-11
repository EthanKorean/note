package com.ethan.note.juso;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 도로명주소 한글 전체분(rnaddrkor_*.txt)의 컬럼 정의와 파싱/바인딩 도구. */
public final class JusoColumns {

    public static final List<String> NAMES = List.of(
            "mng_no", "bjd_code", "sido_nm", "sigungu_nm", "emd_nm", "ri_nm", "san_yn",
            "jibun_main", "jibun_sub", "road_code", "road_nm", "basement_yn", "bld_main", "bld_sub",
            "hjd_code", "hjd_nm", "zip_code", "prev_road_addr", "effective_date", "apt_type",
            "move_reason", "bld_nm", "bld_nm_sigungu");

    public static final int COUNT = NAMES.size();
    public static final String COLUMN_LIST = String.join(",", NAMES);
    public static final String ROW_PLACEHOLDER = "(" + "?,".repeat(COUNT - 1) + "?)";

    private JusoColumns() {
    }

    /**
     * '|' 구분 한 줄을 23개 값으로 나눈다. 빈 값은 null.
     * 원본은 행 끝에 '|'가 하나 더 붙어 있으므로 23개까지만 읽고 나머지는 버린다.
     */
    public static String[] parse(String line) {
        String[] r = new String[COUNT];
        int start = 0;
        for (int i = 0; i < COUNT; i++) {
            int end = line.indexOf('|', start);
            if (end < 0) {
                end = line.length();
            }
            if (start > end) {
                break;   // 필드가 모자란 줄: 나머지는 null (LOAD DATA와 동일)
            }
            r[i] = start == end ? null : line.substring(start, end);
            start = end + 1;
        }
        return r;
    }

    public static void bind(PreparedStatement ps, int offset, String[] row) throws SQLException {
        for (int i = 0; i < COUNT; i++) {
            if (row[i] == null) {
                ps.setNull(offset + i, Types.VARCHAR);
            } else {
                ps.setString(offset + i, row[i]);
            }
        }
    }

    /** LOAD DATA용 사용자 변수 목록: (@c1,...,@c23,@dummy) — 마지막 @dummy는 행 끝의 여분 '|' 용. */
    public static String loadDataVars() {
        return IntStream.rangeClosed(1, COUNT).mapToObj(i -> "@c" + i).collect(Collectors.joining(",")) + ",@dummy";
    }

    /** LOAD DATA용 SET 절: 빈 문자열을 NULL로 바꾼다(다른 적재 방식과 결과를 동일하게 맞춤). */
    public static String loadDataSet() {
        return IntStream.range(0, COUNT)
                .mapToObj(i -> NAMES.get(i) + "=NULLIF(@c" + (i + 1) + ",'')")
                .collect(Collectors.joining(","));
    }
}
