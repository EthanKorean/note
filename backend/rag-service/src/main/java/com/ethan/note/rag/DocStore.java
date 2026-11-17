package com.ethan.note.rag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 검색 대상 문서(폴더의 *.md)를 읽는다. 하위 폴더는 보지 않는다. */
@Component
public class DocStore {

    private final Path dir;
    private final Set<String> exclude;

    public DocStore(@Value("${rag.docs-dir}") String dir, @Value("${rag.docs-exclude:}") String exclude) {
        this.dir = Path.of(dir.isBlank() ? "../../docs" : dir).toAbsolutePath().normalize(); // 비워 둔 값은 기본값으로
        this.exclude = Arrays.stream(exclude.split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    public Path dir() {
        return dir;
    }

    public List<Doc> load() {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("문서 폴더가 없습니다: " + PathMask.show(dir));
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Doc> docs = files.filter(p -> p.getFileName().toString().endsWith(".md") && Files.isRegularFile(p))
                    .filter(p -> !exclude.contains(p.getFileName().toString()))
                    .sorted()
                    .map(p -> {
                        try {
                            return new Doc(p.getFileName().toString(), Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n"));
                        } catch (IOException e) {
                            throw new UncheckedIOException("문서를 읽지 못했습니다: " + p.getFileName(), e); // IOException 메시지에는 전체 경로가 들어 있다
                        }
                    })
                    .toList();
            if (docs.isEmpty()) {
                throw new IllegalArgumentException("문서 폴더에 .md 파일이 없습니다: " + PathMask.show(dir));
            }
            return docs;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
