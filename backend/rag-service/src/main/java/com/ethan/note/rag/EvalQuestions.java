package com.ethan.note.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 검색 평가용 질문 세트(eval-questions.json).
 * 정답은 "조각 번호"가 아니라 "정답 문구"로 적는다. 조각을 자르는 방식이 바뀌어도 같은 질문 세트로 비교하려는 것이다.
 * 조각이 정답 문구를 모두 담고 있으면 그 조각은 정답이다. 문구는 공백을 정리한 형태로 읽어 둔다.
 */
@Component
public class EvalQuestions {

    /** @param evidence 공백을 정리한 정답 문구들 */
    public record Question(String q, List<String> evidence) {
    }

    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

    private final List<Question> questions;
    private List<Doc> lastDocs;
    private List<String> lastUnmatched = List.of();

    public EvalQuestions(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource("eval-questions.json").getInputStream()) {
            List<Question> raw = mapper.readValue(in, new TypeReference<List<Question>>() {
            });
            this.questions = raw.stream().map(q -> new Question(q.q(), q.evidence().stream().map(EvalQuestions::normalize).toList())).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("eval-questions.json 을 읽지 못했습니다", e);
        }
    }

    public List<Question> all() {
        return questions;
    }

    /** @param normalizedText 공백을 정리한 조각 원문 */
    public static boolean relevant(String normalizedText, Question q) {
        for (String e : q.evidence()) {
            if (!normalizedText.contains(e)) {
                return false;
            }
        }
        return true;
    }

    /** 문서에서 정답 문구를 찾을 수 없는 질문(문서가 바뀌어 질문이 낡은 경우). 같은 문서 목록이면 다시 계산하지 않는다. */
    public synchronized List<String> unmatched(List<Doc> docs) {
        if (docs == lastDocs) {
            return lastUnmatched;
        }
        List<String> texts = docs.stream().map(d -> normalize(d.text())).toList();
        List<String> bad = new ArrayList<>();
        for (Question q : questions) {
            boolean found = texts.stream().anyMatch(t -> relevant(t, q));
            if (!found) {
                bad.add(q.q());
            }
        }
        lastDocs = docs;
        lastUnmatched = bad;
        return bad;
    }

    public static String normalize(String s) {
        return WHITESPACE.matcher(s).replaceAll(" ");
    }
}
