package com.ethan.note.juso.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** rnaddr_kor (InnoDB) 매핑. JPA 적재 방식 비교용으로만 사용한다. */
@Entity
@Table(name = "rnaddr_kor")
@Getter
@Setter
@NoArgsConstructor
public class RnaddrKor {

    @Id
    @Column(name = "mng_no")
    private String mngNo;
    @Column(name = "bjd_code")
    private String bjdCode;
    @Column(name = "sido_nm")
    private String sidoNm;
    @Column(name = "sigungu_nm")
    private String sigunguNm;
    @Column(name = "emd_nm")
    private String emdNm;
    @Column(name = "ri_nm")
    private String riNm;
    @Column(name = "san_yn")
    private String sanYn;
    @Column(name = "jibun_main")
    private Integer jibunMain;
    @Column(name = "jibun_sub")
    private Integer jibunSub;
    @Column(name = "road_code")
    private String roadCode;
    @Column(name = "road_nm")
    private String roadNm;
    @Column(name = "basement_yn")
    private String basementYn;
    @Column(name = "bld_main")
    private Integer bldMain;
    @Column(name = "bld_sub")
    private Integer bldSub;
    @Column(name = "hjd_code")
    private String hjdCode;
    @Column(name = "hjd_nm")
    private String hjdNm;
    @Column(name = "zip_code")
    private String zipCode;
    @Column(name = "prev_road_addr")
    private String prevRoadAddr;
    @Column(name = "effective_date")
    private String effectiveDate;
    @Column(name = "apt_type")
    private String aptType;
    @Column(name = "move_reason")
    private String moveReason;
    @Column(name = "bld_nm")
    private String bldNm;
    @Column(name = "bld_nm_sigungu")
    private String bldNmSigungu;

    /** JusoColumns.parse 결과(23개 값)로 엔티티를 만든다. */
    public static RnaddrKor from(String[] r) {
        RnaddrKor e = new RnaddrKor();
        e.mngNo = r[0];
        e.bjdCode = r[1];
        e.sidoNm = r[2];
        e.sigunguNm = r[3];
        e.emdNm = r[4];
        e.riNm = r[5];
        e.sanYn = r[6];
        e.jibunMain = toInt(r[7]);
        e.jibunSub = toInt(r[8]);
        e.roadCode = r[9];
        e.roadNm = r[10];
        e.basementYn = r[11];
        e.bldMain = toInt(r[12]);
        e.bldSub = toInt(r[13]);
        e.hjdCode = r[14];
        e.hjdNm = r[15];
        e.zipCode = r[16];
        e.prevRoadAddr = r[17];
        e.effectiveDate = r[18];
        e.aptType = r[19];
        e.moveReason = r[20];
        e.bldNm = r[21];
        e.bldNmSigungu = r[22];
        return e;
    }

    private static Integer toInt(String s) {
        return s == null ? null : Integer.valueOf(s);
    }
}
