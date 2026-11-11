package com.ethan.note.juso.loader;

import org.springframework.stereotype.Component;

/** LOAD DATA LOCAL INFILE — 앱(클라이언트)이 파일을 읽어 DB로 스트리밍. */
@Component
public class LoadDataLocalLoader extends LoadDataLoader {

    public LoadDataLocalLoader(JusoDb db) {
        super(db, true);
    }

    @Override
    public String name() {
        return "loaddata-local";
    }
}
