package com.ethan.note.juso.mybatis;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL 은 resources/mapper/JusoMapper.xml. table 은 JusoTables 화이트리스트 값만 들어온다. */
@Mapper
public interface JusoMapper {

    int insertOne(@Param("table") String table, @Param("row") String[] row);

    int insertMulti(@Param("table") String table, @Param("rows") List<Map<String, String>> rows);
}
