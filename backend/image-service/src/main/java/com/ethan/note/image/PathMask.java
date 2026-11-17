package com.ethan.note.image;

import java.nio.file.Path;

/**
 * 화면과 API 응답에 나가는 경로에서 내 컴퓨터의 실제 경로(사용자 이름 등)를 가린다.
 * 프로젝트 안의 경로는 "${projectRoot}/docs" 처럼, 홈 폴더 안은 "~/..." 로, 그 밖은 마지막 폴더 이름만 보여 준다.
 * 프로젝트 루트는 환경변수 NOTE_ROOT 로 받는다(scripts/run-backend.sh 가 넣어 준다).
 */
final class PathMask {

    private static final String ROOT = System.getenv("NOTE_ROOT");
    private static final String HOME = System.getProperty("user.home", "");

    private PathMask() {
    }

    static String show(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        if (ROOT != null && !ROOT.isBlank()) {
            Path root = Path.of(ROOT).toAbsolutePath().normalize();
            if (abs.startsWith(root)) {
                return "${projectRoot}" + (abs.equals(root) ? "" : "/" + root.relativize(abs).toString().replace('\\', '/'));
            }
        }
        if (!HOME.isBlank()) {
            Path home = Path.of(HOME).toAbsolutePath().normalize();
            if (abs.startsWith(home) && !abs.equals(home)) {
                return "~/" + home.relativize(abs).toString().replace('\\', '/');
            }
        }
        Path name = abs.getFileName();
        return name == null ? "(경로 숨김)" : ".../" + name;
    }

    /** 예외 메시지처럼 경로가 글 속에 섞여 있는 문자열에서 프로젝트 루트와 홈 폴더를 가린다. */
    static String scrub(String s) {
        if (s == null) {
            return null;
        }
        String out = s;
        if (ROOT != null && !ROOT.isBlank()) {
            out = out.replace(Path.of(ROOT).toAbsolutePath().normalize().toString(), "${projectRoot}");
        }
        if (!HOME.isBlank()) {
            out = out.replace(HOME, "~");
        }
        return out;
    }
}
