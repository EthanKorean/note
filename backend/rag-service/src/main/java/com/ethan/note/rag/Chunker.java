package com.ethan.note.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 문서를 조각으로 자른다. 방식 세 가지를 같은 질문 세트로 비교하려고 한 곳에 모았다. */
@Component
public class Chunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    /** 본문에서 잘라 낸 글과 그 시작 위치. */
    private record Span(int start, String text) {
    }

    /** 제목 하나가 이끄는 구간. path 는 상위 제목을 포함한 경로. */
    private record Section(int start, String path, String text) {
    }

    public List<Chunk> chunk(List<Doc> docs, ChunkConfig cfg) {
        List<Chunk> out = new ArrayList<>();
        for (Doc d : docs) {
            List<Section> sections = sections(d.text());
            switch (cfg.type()) {
                case "fixed" -> fixed(d, cfg, sections, out);
                case "paragraph" -> paragraph(d, cfg, sections, out);
                default -> heading(d, cfg, sections, out);
            }
        }
        return out;
    }

    private void fixed(Doc d, ChunkConfig cfg, List<Section> sections, List<Chunk> out) {
        String text = d.text();
        int step = cfg.size() - cfg.overlap();
        for (int s = 0; s < text.length(); s += step) {
            int e = Math.min(text.length(), s + cfg.size());
            String piece = text.substring(s, e).strip();
            if (!piece.isEmpty()) {
                out.add(new Chunk(out.size(), d.name(), headingAt(sections, s), piece, piece));
            }
            if (e == text.length()) {
                break;
            }
        }
    }

    private void paragraph(Doc d, ChunkConfig cfg, List<Section> sections, List<Chunk> out) {
        for (Span sp : mergeParagraphs(d.text(), cfg.size())) {
            out.add(new Chunk(out.size(), d.name(), headingAt(sections, sp.start()), sp.text(), sp.text()));
        }
    }

    private void heading(Doc d, ChunkConfig cfg, List<Section> sections, List<Chunk> out) {
        for (Section sec : sections) {
            if (isHeadingOnly(sec.text())) {
                continue; // 아래에 바로 하위 제목이 오는 제목은 내용이 없다
            }
            List<String> parts = sec.text().length() <= cfg.size()
                    ? List.of(sec.text())
                    : mergeParagraphs(sec.text(), cfg.size()).stream().map(Span::text).toList();
            for (String part : parts) {
                String prefix = sec.path().isEmpty() ? d.name() : d.name() + " > " + sec.path();
                out.add(new Chunk(out.size(), d.name(), sec.path(), part, prefix + "\n" + part));
            }
        }
    }

    private static boolean isHeadingOnly(String text) {
        if (text.isEmpty()) {
            return true;
        }
        int nl = text.indexOf('\n');
        return text.startsWith("#") && (nl < 0 || text.substring(nl + 1).isBlank());
    }

    /**
     * 빈 줄로 나눈 문단을 size 를 넘지 않을 때까지 이어 붙인다. 문단 하나가 size 보다 길면 그대로 둔다.
     * 코드 블록(```) 안의 빈 줄은 문단 경계가 아니다(코드 예제가 중간에서 잘리지 않게).
     */
    private static List<Span> mergeParagraphs(String text, int size) {
        List<Span> out = new ArrayList<>();
        List<int[]> ranges = new ArrayList<>();
        boolean fence = false;
        int pos = 0;
        int start = 0;
        for (String line : text.split("\n", -1)) {
            int lineStart = pos;
            pos += line.length() + 1;
            if (line.stripLeading().startsWith("```")) {
                fence = !fence;
            } else if (!fence && line.isBlank()) {
                ranges.add(new int[] {start, lineStart});
                start = Math.min(pos, text.length());
            }
        }
        ranges.add(new int[] {start, text.length()});

        StringBuilder cur = new StringBuilder();
        int curStart = 0;
        for (int[] r : ranges) {
            String p = text.substring(r[0], r[1]).strip();
            if (p.isEmpty()) {
                continue;
            }
            if (cur.length() > 0 && cur.length() + 2 + p.length() > size) {
                out.add(new Span(curStart, cur.toString()));
                cur.setLength(0);
            }
            if (cur.length() == 0) {
                curStart = r[0];
            } else {
                cur.append("\n\n");
            }
            cur.append(p);
        }
        if (cur.length() > 0) {
            out.add(new Span(curStart, cur.toString()));
        }
        return out;
    }

    /** 마크다운 제목(#)으로 구간을 나눈다. 코드 블록(```) 안의 # 은 제목이 아니다. */
    private static List<Section> sections(String text) {
        List<int[]> starts = new ArrayList<>(); // {시작 위치}
        List<String> paths = new ArrayList<>();
        String[] stack = new String[7];
        starts.add(new int[] {0});
        paths.add("");

        boolean fence = false;
        int pos = 0;
        for (String line : text.split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) {
                fence = !fence;
            } else if (!fence) {
                Matcher m = HEADING.matcher(line);
                if (m.matches()) {
                    int level = m.group(1).length();
                    stack[level] = m.group(2);
                    for (int i = level + 1; i < stack.length; i++) {
                        stack[i] = null;
                    }
                    List<String> parts = new ArrayList<>();
                    for (int i = 1; i <= level; i++) {
                        if (stack[i] != null) {
                            parts.add(stack[i]);
                        }
                    }
                    starts.add(new int[] {pos});
                    paths.add(String.join(" > ", parts));
                }
            }
            pos += line.length() + 1;
        }

        List<Section> out = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int s = starts.get(i)[0];
            int e = i + 1 < starts.size() ? starts.get(i + 1)[0] : text.length();
            String body = text.substring(Math.min(s, text.length()), Math.min(e, text.length())).strip();
            if (!body.isEmpty()) {
                out.add(new Section(s, paths.get(i), body));
            }
        }
        return out;
    }

    private static String headingAt(List<Section> sections, int offset) {
        String path = "";
        for (Section s : sections) {
            if (s.start() > offset) {
                break;
            }
            path = s.path();
        }
        return path;
    }
}
