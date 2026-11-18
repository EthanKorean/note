import type { CodeBlock } from './methodSamples'

/**
 * AI 용어 정리의 "예제 보기". 모든 예제가 같은 주제 하나를 쓴다: **해든 카페 안내 챗봇**(메뉴, 영업시간, 알레르기, 환불).
 * 카페는 누구나 아는 곳이고, 문서(RAG), 기능(tool use), 주문(구조화 출력, 에이전트)까지 한 주제로 이어 설명할 수 있다.
 *
 * kind 'claude': Claude API(Java SDK)를 쓰는 예제. API 키가 없어서 이 프로젝트에서 실행해 보지 않았고, SDK 문서에 있는 형태로 작성했다.
 * kind 'ours'  : 이 프로젝트에 실제로 있는 방식을 카페 예로 설명한 것(코드는 이 프로젝트 코드를 줄인 것).
 */
export interface DemoBlock {
  lang: CodeBlock['lang'] | 'text'
  title: string
  code: string
}

export interface Demo {
  kind: 'claude' | 'ours'
  title: string
  /** 카페 안내 챗봇에서 이 용어가 하는 일 */
  situation: string
  steps: string[]
  blocks: DemoBlock[]
  /** 실행하면 이렇게 나온다(예시). 실제 응답은 달라질 수 있다 */
  output?: { title: string; text: string }
  note?: string
  link?: { path: string; label: string }
}

/** 예제에서 쓰는 모델 ID. 바뀌면 여기만 고친다. */
const MODEL = 'claude-opus-5-5'

const CAFE_DOC = `# 해든 카페 안내

## 영업시간
평일 08:00 ~ 21:00, 주말 10:00 ~ 18:00. 매월 첫째 월요일은 쉽니다.

## 메뉴와 가격
아메리카노 4,500원 / 카페라떼 5,000원 / 말차라떼 5,500원 / 치즈케이크 6,500원

## 알레르기
카페라떼와 치즈케이크에는 우유가, 말차라떼에는 대두가 들어 있습니다.

## 환불
음료는 제조를 시작하기 전에만 취소·환불됩니다. 디저트는 받은 날 포장을 뜯지 않았을 때 환불됩니다.`

const cafeDoc: DemoBlock = { lang: 'text', title: '카페 안내 문서 (예제에서 계속 쓰는 문서)', code: CAFE_DOC }

const IMPORTS_BASE = String.raw`import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;`

const printText = String.raw`response.content().stream()
    .flatMap(block -> block.text().stream())
    .forEach(text -> System.out.println(text.text()));`

export const demos: Record<string, Demo> = {
  RAG: {
    kind: 'claude',
    title: '손님 질문에 카페 문서로 답하기',
    situation: '손님이 "말차라떼에 우유 들어가요?"라고 묻는다. 문서 전체를 넣지 않고, 관련 있는 조각만 찾아 Claude에게 붙여 주고 그 내용으로만 답하게 한다.',
    steps: [
      '카페 안내 문서를 조각으로 잘라 벡터로 바꿔 둔다 (청킹 + 임베딩. 이 프로젝트의 rag-service가 하는 일)',
      '질문과 가까운 조각 3개를 찾는다 (찾기)',
      '찾은 조각을 프롬프트에 붙여 Claude에게 묻는다 (생성)',
      '답을 손님에게 보여 준다. 근거가 된 조각도 같이 보여 줄 수 있다',
    ],
    blocks: [
      cafeDoc,
      {
        lang: 'java',
        title: '찾은 조각을 붙여 Claude에게 묻기 (Java, Anthropic SDK)',
        code: String.raw`${IMPORTS_BASE}
import java.util.List;

AnthropicClient client = AnthropicOkHttpClient.fromEnv(); // 환경변수 ANTHROPIC_API_KEY

String question = "말차라떼에 우유 들어가요?";
// 찾기: 질문과 가까운 조각 3개. 이 프로젝트의 GET /api/rag/search 와 같은 일을 하는 함수
List<String> chunks = searchTopChunks(question, 3);

MessageCreateParams params = MessageCreateParams.builder()
    .model("${MODEL}")
    .maxTokens(1000L)
    .system("당신은 해든 카페 안내 도우미입니다. 아래 <문서>의 내용으로만 답하세요.")
    .addUserMessage("<문서>\n" + String.join("\n---\n", chunks) + "\n</문서>\n\n질문: " + question)
    .build();

Message response = client.messages().create(params);
${printText}`,
      },
    ],
    output: { title: '예상 출력 (실제 응답은 달라질 수 있다)', text: '말차라떼에는 우유가 아니라 대두가 들어 있다고 안내돼 있습니다. (알레르기 안내 기준)' },
    note: '핵심은 "찾기"다. 알레르기 조각을 못 찾아 오면 Claude는 근거 없이 답하게 된다. 그래서 찾기를 먼저 숫자로 잰다(RAG 검색 화면).',
    link: { path: '/rag', label: 'RAG 검색 화면에서 "찾기" 단계를 직접 해 보기' },
  },

  청킹: {
    kind: 'ours',
    title: '카페 문서를 어떻게 자르면 좋을까',
    situation: '"환불되나요?"라는 질문에는 환불 규정이 한 조각에 온전히 들어 있어야 한다. 규정이 두 조각에 걸리면 한쪽만 찾아 와서 답이 틀릴 수 있다.',
    steps: [
      '고정 길이: 글자 수로 기계적으로 자른다. 문장 중간이 끊길 수 있다',
      '제목 단위: 영업시간 / 메뉴 / 알레르기 / 환불처럼 제목마다 한 조각으로 자른다',
      '같은 질문 세트로 두 방식의 정답 찾는 비율을 비교한다 (시나리오 1번)',
    ],
    blocks: [
      cafeDoc,
      {
        lang: 'java',
        title: '고정 길이 청킹 (이 프로젝트 Chunker 를 줄인 것)',
        code: String.raw`// size 글자씩 자르고, 앞 조각과 overlap 글자를 겹친다
for (int s = 0; s < text.length(); s += size - overlap) {
    int e = Math.min(text.length(), s + size);
    chunks.add(text.substring(s, e).strip());
    if (e == text.length()) break;
}`,
      },
    ],
    output: {
      title: '개념 그림 (글자 수는 대략)',
      text: String.raw`[고정 80자]
 조각1  # 해든 카페 안내 ## 영업시간 평일 08:00 ~ 21:00 ... ## 메뉴와 가격 아메리카노 4,500원 ...
 조각2  ... 치즈케이크 6,500원 ## 알레르기 카페라떼와 치즈케이크에는 우유가, 말차  ← 알레르기 문장이 끊김
 조각3  라떼에는 대두가 들어 있습니다. ## 환불 음료는 제조를 ...

[제목 단위]
 조각1  ## 영업시간 ...    조각2  ## 메뉴와 가격 ...
 조각3  ## 알레르기 ...    조각4  ## 환불 ...    ← 질문 하나에 필요한 내용이 한 조각에 온전히 들어 있음`,
    },
    note: '실제 측정에서는 제목 단위가 같은 검색 방식에서 가장 높거나 비슷했다(docs/ax/rag.md 4.1). 이 문서들의 구조 때문일 수 있어 내 문서로 재 봐야 한다.',
    link: { path: '/rag', label: '시나리오 1번: 청킹 방식 비교 실행해 보기' },
  },

  임베딩: {
    kind: 'ours',
    title: '단어가 달라도 뜻이 가까운 문장 찾기',
    situation: '손님은 "우유 못 마시는데 말차라떼 괜찮아요?"라고 묻는다. 문서에는 "우유"와 "알레르기"라는 말이 따로 있고 "못 마신다"는 말은 없다. 글자로는 안 맞아도 뜻이 가까우면 찾을 수 있어야 한다.',
    steps: [
      '글을 숫자 배열(벡터)로 바꾼다. 이 프로젝트는 multilingual-e5-small 모델을 서비스 안에서 직접 돌린다',
      '질문에는 "query: ", 문서 조각에는 "passage: " 를 앞에 붙인다 (e5 모델의 규칙)',
      '벡터가 가까운 정도를 코사인 유사도로 잰다',
    ],
    blocks: [
      {
        lang: 'java',
        title: '유사도 계산 (이 프로젝트 SearchIndex 를 줄인 것)',
        code: String.raw`// 두 벡터가 길이 1로 정규화돼 있으면 내적이 곧 코사인 유사도다
double dot = 0;
for (int i = 0; i < q.length; i++) {
    dot += (double) q[i] * v[i];
}`,
      },
    ],
    output: {
      title: '설명용 가짜 숫자 (실제 벡터는 384차원)',
      text: String.raw`질문 "우유 못 마시는데 말차라떼 괜찮아요?"  → [0.8, 0.5, 0.1]
조각 "알레르기: ...말차라떼에는 대두가..."        → [0.7, 0.6, 0.2]   유사도 0.98  ← 가까움
조각 "영업시간: 평일 08:00 ~ 21:00 ..."           → [0.1, 0.2, 0.9]   유사도 0.31  ← 멀다`,
    },
    note: 'Claude는 임베딩 모델을 제공하지 않는다. 임베딩은 별도 모델(이 프로젝트는 로컬 e5, 또는 외부 임베딩 API)로 한다.',
    link: { path: '/rag', label: '질문해 보기에서 벡터 검색으로 직접 확인하기' },
  },

  '벡터 검색 / 벡터 DB': {
    kind: 'ours',
    title: '질문과 가장 가까운 조각 찾기',
    situation: '카페 문서가 조각 4개일 때는 전부 비교해도 순식간이다. 메뉴판과 규정이 수십만 조각으로 늘면 전부 비교하는 방식은 느려진다.',
    steps: [
      '질문을 벡터로 바꾼다',
      '모든 조각 벡터와 유사도를 계산한다 (이 프로젝트는 조각 수십~수백 개라 전부 비교)',
      '점수가 큰 순서로 k개를 돌려준다',
      '조각이 아주 많아지면 "근사 검색 색인"을 가진 벡터 DB(pgvector 등)가 필요하다 (이 프로젝트에서는 아직 해 보지 않음)',
    ],
    blocks: [
      {
        lang: 'java',
        title: '전부 비교하고 상위 k개 (이 프로젝트 SearchIndex 를 줄인 것)',
        code: String.raw`double[] scores = new double[vectors.length];
for (int i = 0; i < vectors.length; i++) {
    scores[i] = dot(queryVec, vectors[i]);
}
// 점수가 높은 순서로 정렬해 앞의 k개를 돌려준다`,
      },
    ],
    output: {
      title: '질문: "말차라떼 우유?"',
      text: String.raw`1위  알레르기   0.98
2위  환불       0.35
3위  메뉴와 가격 0.33
4위  영업시간   0.31`,
    },
    link: { path: '/rag', label: '질문해 보기에서 검색 방식을 벡터로 두고 해 보기' },
  },

  '키워드 검색 (BM25)': {
    kind: 'ours',
    title: '"5,500원" 같은 정확한 말로 찾기',
    situation: '손님이 "5,500원짜리 음료가 뭐예요?"라고 묻는다. 숫자는 뜻이 아니라 글자가 정확히 맞아야 한다. 이런 질문은 키워드 검색이 강하다.',
    steps: [
      '질문의 단어(5,500원, 음료)가 각 조각에 나오는지 센다',
      '여러 조각에 흔한 단어는 점수를 낮추고, 드문 단어는 높인다 (idf)',
      '한국어는 조사가 붙어서("말차라떼에는") 단어를 글자 2개씩으로도 쪼갠다. 한 글자 조사는 뺀다',
    ],
    blocks: [
      {
        lang: 'java',
        title: 'BM25 점수 한 단어분 (이 프로젝트 Bm25 를 줄인 것)',
        code: String.raw`// df: 그 단어가 나온 조각 수, f: 이 조각에서 나온 횟수, len/avgLen: 조각 길이와 평균 길이
double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
score += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * len / avgLen));  // K1=1.5, B=0.75`,
      },
    ],
    output: {
      title: '질문: "5,500원 말차라떼"',
      text: String.raw`1위  메뉴와 가격   ← "말차라떼"와 "5,500원"이 둘 다 있음
2위  알레르기       ← "말차라떼"만 있음
(그 외 조각은 겹치는 단어가 없어 결과에 나오지 않음)`,
    },
    note: '동의어에는 약하다. "커피"라고 물으면 문서의 "아메리카노"를 못 찾는다.',
    link: { path: '/rag', label: '질문해 보기에서 검색 방식을 키워드로 두고 해 보기' },
  },

  '하이브리드 검색': {
    kind: 'ours',
    title: '뜻 검색과 단어 검색을 합치기',
    situation: '"말차라떼 5,500원 환불되나요?"에는 뜻(환불)과 정확한 말(말차라떼, 5,500원)이 섞여 있다. 두 검색의 순위를 합치면 한쪽의 약점을 다른 쪽이 메워 준다.',
    steps: [
      '벡터 검색과 키워드 검색을 각각 돌려 순위를 얻는다',
      '조각마다 1/(60+순위)를 더한다 (RRF). 점수의 크기가 달라도 순위만 쓰면 합칠 수 있다',
      '합친 점수가 큰 순서로 돌려준다',
    ],
    blocks: [
      {
        lang: 'java',
        title: 'RRF 합치기 (이 프로젝트 SearchIndex 를 줄인 것)',
        code: String.raw`Map<Integer, Double> fused = new HashMap<>();
for (List<Hit> list : List.of(byVector, byBm25)) {
    for (int rank = 0; rank < list.size(); rank++) {
        fused.merge(list.get(rank).chunk().id(), 1.0 / (60 + rank + 1), Double::sum);
    }
}`,
      },
    ],
    output: {
      title: '계산 예 (순위는 설명용)',
      text: String.raw`            벡터 순위   키워드 순위   합친 점수
환불            1           2         1/61 + 1/62 = 0.0325   ← 1위
메뉴와 가격      3           1         1/63 + 1/61 = 0.0323
알레르기         2           3         1/62 + 1/63 = 0.0320`,
    },
    note: '실제 측정에서 하이브리드가 항상 이기지는 않았다. 조각 크기와 청킹에 따라 키워드 단독이 앞서기도 했다(docs/ax/rag.md 4.2).',
    link: { path: '/rag', label: '시나리오 2번: 검색 방식 비교 실행해 보기' },
  },

  '검색 평가 (hit@k, MRR)': {
    kind: 'ours',
    title: '카페 질문 세트로 검색 점수 매기기',
    situation: '청킹이나 검색 방식을 바꿨을 때 "좋아진 것 같다"가 아니라 같은 질문 세트의 점수로 말하려면, 질문마다 정답 문구를 정해 둬야 한다.',
    steps: [
      '질문과 정답 문구를 적어 둔다 (정답은 조각 번호가 아니라 문구. 자르는 방식이 바뀌어도 비교할 수 있다)',
      '질문마다 검색해서 정답 문구가 든 조각이 몇 번째에 나왔는지 본다',
      'hit@k = 상위 k개 안에 정답이 있는 질문의 비율, MRR = 정답 순위의 역수 평균',
    ],
    blocks: [
      {
        lang: 'typescript',
        title: '질문 세트 형식 (eval-questions.json)',
        code: String.raw`[
  { "q": "말차라떼에 우유 들어가요?", "evidence": ["말차라떼에는 대두가"] },
  { "q": "주말에 몇 시까지 해요?",     "evidence": ["주말 10:00 ~ 18:00"] },
  { "q": "치즈케이크 환불되나요?",     "evidence": ["포장을 뜯지 않았을 때 환불"] }
]`,
      },
      {
        lang: 'java',
        title: '점수 계산 (이 프로젝트 EvalRunner 를 줄인 것)',
        code: String.raw`// rank: 정답 조각이 처음 나온 순위(1부터). 상위 5개에 없으면 0
if (rank == 0) continue;
rr += 1.0 / rank;
hit5++;
if (rank <= 3) hit3++;
if (rank == 1) hit1++;`,
      },
    ],
    output: {
      title: '질문 3개 예 (정답 순위 1, 3, 못 찾음)',
      text: String.raw`hit@1 = 1/3 = 33.3%
hit@3 = 2/3 = 66.7%
MRR   = (1 + 1/3 + 0) / 3 = 0.444`,
    },
    note: '질문이 적으면 질문 하나가 크게 흔든다(이 프로젝트는 35개라 하나에 2.9%p). 큰 조각에 유리한 지표라는 한계도 있다.',
    link: { path: '/rag', label: '시나리오로 평가 결과 직접 보기' },
  },

  할루시네이션: {
    kind: 'claude',
    title: '메뉴에 없는 걸 물으면 지어내지 않게',
    situation: '손님이 "피자도 팔아요?"라고 묻는다. 문서에 없는 내용이다. 모델이 그럴듯하게 "네, 마르게리타가 있어요"라고 지어내면 손님은 믿고 방문한다.',
    steps: [
      '시스템 프롬프트에 규칙을 둔다: 문서에 없으면 "안내 문서에 없습니다"라고 답한다',
      '답에 근거가 된 문장을 함께 적게 한다 (사람이 확인할 수 있게)',
      '문서에 있는 질문과 없는 질문을 섞어 평가해서 "없다"고 답하는지 확인한다',
    ],
    blocks: [
      cafeDoc,
      {
        lang: 'java',
        title: '규칙을 둔 시스템 프롬프트 (Java, Anthropic SDK)',
        code: String.raw`${IMPORTS_BASE}

AnthropicClient client = AnthropicOkHttpClient.fromEnv();

String rules = """
    당신은 해든 카페 안내 도우미입니다.
    - 아래 <문서>에 적힌 내용으로만 답하세요.
    - 문서에 없는 내용은 추측하지 말고 "안내 문서에 없습니다. 직원에게 문의해 주세요."라고 답하세요.
    - 답 끝에 근거가 된 문서 문장을 [근거: ...] 로 붙이세요.
    """;

MessageCreateParams params = MessageCreateParams.builder()
    .model("${MODEL}")
    .maxTokens(1000L)
    .system(rules)
    .addUserMessage("<문서>\n" + cafeDoc + "\n</문서>\n\n질문: 피자도 팔아요?")
    .build();

Message response = client.messages().create(params);
${printText}`,
      },
    ],
    output: {
      title: '예상 출력 (실제 응답은 달라질 수 있다)',
      text: String.raw`[문서에 있는 질문] 말차라떼에 우유 들어가요?
→ 우유는 들어 있지 않고 대두가 들어 있습니다. [근거: 말차라떼에는 대두가 들어 있습니다.]

[문서에 없는 질문] 피자도 팔아요?
→ 안내 문서에 없습니다. 직원에게 문의해 주세요.`,
    },
    note: '프롬프트로 줄일 수는 있지만 완전히 막지는 못한다. 그래서 근거를 같이 받아 사람이 확인하게 하고, 문서에 없는 질문을 평가 세트에 넣어 둔다. 검색은 못 찾았다는 신호 없이 "가장 가까운 것"을 돌려준다는 점이 이 문제의 출발점이다(RAG 검색 > 시나리오 6번).',
    link: { path: '/rag', label: '시나리오 6번: 문서에 없는 질문 직접 해 보기' },
  },

  토큰: {
    kind: 'claude',
    title: '카페 문서가 몇 토큰인지 세어 보기',
    situation: '메뉴판과 규정이 길어지면 매 질문마다 보내는 글의 양(토큰)이 비용과 속도를 정한다. 보내기 전에 미리 세어 볼 수 있다.',
    steps: [
      '요청을 실제로 보내기 전에 토큰 수만 세는 API(count_tokens)를 쓴다',
      '글자 수로 어림하지 않는다. 한국어와 영어, 표와 코드는 토큰 비율이 다르다',
    ],
    blocks: [
      {
        lang: 'java',
        title: '토큰 수 세기 (Java, Anthropic SDK)',
        code: String.raw`import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCountTokensParams;

AnthropicClient client = AnthropicOkHttpClient.fromEnv();

long tokens = client.messages().countTokens(
    MessageCountTokensParams.builder()
        .model("${MODEL}")
        .addUserMessage(cafeDoc)
        .build()
).inputTokens();

System.out.println("카페 안내 문서: " + tokens + " 토큰");`,
      },
    ],
    output: { title: '출력', text: '카페 안내 문서: (숫자) 토큰   ← 모델마다 토크나이저가 달라서 같은 글도 숫자가 다르다' },
    note: '이 프로젝트의 임베딩 모델(e5)은 한국어가 약 1.7자당 1토큰이었다. Claude의 토크나이저는 다르므로 그 비율을 그대로 쓰면 안 되고 count_tokens 로 직접 세야 한다.',
  },

  '컨텍스트 윈도우': {
    kind: 'claude',
    title: '문서 전체 vs 찾은 조각 3개',
    situation: '카페 문서가 짧을 때는 통째로 넣어도 된다. 메뉴 200개와 규정 전부가 되면 한 번에 넣기 어렵고, 넣더라도 느리고 비싸다. 필요한 조각만 넣으면 얼마나 줄어드는지 비교한다.',
    steps: [
      '문서 전체를 넣었을 때의 토큰 수를 센다',
      '찾은 조각 3개만 넣었을 때의 토큰 수를 센다',
      '둘의 차이가 매 질문마다 반복되는 비용 차이다',
    ],
    blocks: [
      {
        lang: 'java',
        title: '두 가지를 세어 비교 (Java, Anthropic SDK)',
        code: String.raw`import com.anthropic.models.messages.MessageCountTokensParams;

long full = client.messages().countTokens(
    MessageCountTokensParams.builder()
        .model("${MODEL}")
        .addUserMessage(cafeDoc + "\n\n질문: " + question)
        .build()).inputTokens();

long rag = client.messages().countTokens(
    MessageCountTokensParams.builder()
        .model("${MODEL}")
        .addUserMessage(String.join("\n---\n", chunks) + "\n\n질문: " + question)
        .build()).inputTokens();

System.out.println("전체 문서: " + full + " / 찾은 조각: " + rag);`,
      },
    ],
    output: { title: '출력', text: '전체 문서: (큰 수) / 찾은 조각: (작은 수)   ← 문서가 길수록 차이가 커진다' },
    note: '컨텍스트 윈도우(한 번에 볼 수 있는 최대 토큰)는 모델마다 다르다. 다 들어가더라도 필요한 부분만 넣는 편이 보통 낫다.',
  },

  '시스템 프롬프트': {
    kind: 'claude',
    title: '카페 도우미의 역할과 규칙 정하기',
    situation: '손님마다 같은 말투와 같은 규칙으로 답하게 하려면, 대화가 시작되기 전에 역할과 규칙을 정해 둬야 한다. 이게 시스템 프롬프트다.',
    steps: [
      '역할(해든 카페 도우미), 말투(존댓말, 두 문장 이내), 금지(가격 추측, 의학적 조언)를 적는다',
      '손님의 질문은 user 메시지로, 규칙은 system 으로 따로 보낸다',
      '규칙을 바꿔 가며 같은 질문 세트의 결과를 비교한다 (프롬프트 엔지니어링)',
    ],
    blocks: [
      {
        lang: 'java',
        title: '시스템 프롬프트와 질문 보내기 (Java, Anthropic SDK)',
        code: String.raw`${IMPORTS_BASE}

AnthropicClient client = AnthropicOkHttpClient.fromEnv();

String system = """
    당신은 해든 카페 안내 도우미입니다.
    - 존댓말로, 두 문장 이내로 답하세요.
    - 가격은 안내 문서에 적힌 값만 말하고, 없으면 모른다고 하세요.
    - 알레르기 질문에는 "정확한 확인은 직원에게"라는 말을 덧붙이세요.
    """;

MessageCreateParams params = MessageCreateParams.builder()
    .model("${MODEL}")
    .maxTokens(500L)
    .system(system)                            // 규칙
    .addUserMessage("말차라떼 가격이 얼마예요?")   // 손님 질문
    .build();

Message response = client.messages().create(params);
${printText}`,
      },
    ],
    output: { title: '예상 출력 (실제 응답은 달라질 수 있다)', text: '(안내 문서를 함께 주지 않았다면) 가격 정보가 없어 정확한 가격은 말씀드리기 어렵습니다. 매장에 문의해 주세요.' },
    note: '규칙이 문서(근거)를 대신하지는 못한다. 위 예에서 모델이 가격을 모르는 것이 정상이다. 규칙과 문서를 함께 줘야 한다(RAG 예제).',
  },

  'tool use (함수 호출)': {
    kind: 'claude',
    title: '메뉴 가격을 함수로 조회하기',
    situation: '가격이 자주 바뀌면 문서에 적어 두기보다 메뉴 DB에서 조회하는 편이 정확하다. Claude가 "가격 조회 함수를 이 메뉴로 호출해 줘"라고 요청하고, 우리 코드가 DB를 조회해서 돌려준다. Claude가 직접 실행하는 것이 아니다.',
    steps: [
      '함수를 도구로 정의한다(이름, 설명, 인자). 설명이 좋아야 Claude가 언제 쓸지 안다',
      'Claude가 필요하다고 판단하면 도구 호출 요청을 보낸다 (예: menu = "말차라떼")',
      '우리 코드가 실행해서 결과(5500원)를 돌려준다',
      'Claude가 결과를 읽고 손님에게 답한다',
    ],
    blocks: [
      {
        lang: 'java',
        title: '도구 정의와 실행 (Java, Anthropic SDK의 Tool Runner)',
        code: String.raw`import com.anthropic.helpers.BetaToolRunner;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.function.Supplier;

@JsonClassDescription("카페 메뉴의 가격(원)을 조회한다")
static class GetMenuPrice implements Supplier<String> {
    @JsonPropertyDescription("메뉴 이름, 예: 말차라떼")
    public String menu;

    @Override
    public String get() {
        return menuRepository.findPrice(menu) + "원";   // 우리 코드가 DB 를 조회한다
    }
}

BetaToolRunner toolRunner = client.beta().messages().toolRunner(
    MessageCreateParams.builder()
        .model("${MODEL}")
        .maxTokens(16000L)
        .putAdditionalHeader("anthropic-beta", "structured-outputs-2025-11-13") // Java Tool Runner 가 요구하는 beta 헤더(SDK 문서 기준)
        .addTool(GetMenuPrice.class)
        .addUserMessage("말차라떼 가격이 얼마예요?")
        .build());

for (BetaMessage message : toolRunner) {
    System.out.println(message);
}`,
      },
    ],
    output: {
      title: '진행 (예시)',
      text: String.raw`1) Claude → 도구 호출 요청: get_menu_price(menu = "말차라떼")
2) 우리 코드 실행 → "5500원"
3) Claude → "말차라떼는 5,500원입니다."`,
    },
    note: 'Tool Runner가 "요청 → 실행 → 결과 전달" 반복을 대신해 준다. 반복을 직접 짜면 도구 실패, 반복 횟수 상한도 직접 처리해야 한다.',
  },

  '에이전트 루프': {
    kind: 'claude',
    title: '주문 받기: 재고 확인 → 가격 계산 → 주문 생성',
    situation: '"치즈케이크 두 개 주문할게요"는 한 번의 조회로 끝나지 않는다. 재고가 있는지 확인하고, 가격을 계산하고, 주문을 만든다. 다음 단계를 Claude가 결과를 보고 정하게 한다.',
    steps: [
      '도구 3개를 준다: 재고 확인, 가격 조회, 주문 생성',
      'Claude가 도구를 부르면 결과를 돌려주고, 결과를 보고 다음 도구를 정한다',
      '더 부를 도구가 없으면 최종 답을 낸다',
      '무한 반복을 막으려고 반복 횟수 상한을 둔다. 주문 생성처럼 되돌리기 어려운 도구는 사람 확인을 거치게 한다',
    ],
    blocks: [
      {
        lang: 'java',
        title: '도구 3개를 주고 루프 돌리기 (Java, Anthropic SDK의 Tool Runner)',
        code: String.raw`// 도구 클래스는 tool use 예제와 같은 방식(Supplier<String> 구현)으로 만든다
BetaToolRunner toolRunner = client.beta().messages().toolRunner(
    MessageCreateParams.builder()
        .model("${MODEL}")
        .maxTokens(16000L)
        .putAdditionalHeader("anthropic-beta", "structured-outputs-2025-11-13") // Java Tool Runner 가 요구하는 beta 헤더(SDK 문서 기준)
        .addTool(CheckStock.class)      // 재고 확인
        .addTool(GetMenuPrice.class)    // 가격 조회
        .addTool(CreateOrder.class)     // 주문 생성
        .addUserMessage("치즈케이크 두 개 주문할게요")
        .build());

int turns = 0;
for (BetaMessage message : toolRunner) {     // 반복 한 번이 Claude 응답 한 번
    System.out.println(message);
    if (++turns >= 10) break;                // 반복 상한
}`,
      },
    ],
    output: {
      title: '진행 (예시)',
      text: String.raw`1) check_stock("치즈케이크")        → "재고 5개"
2) get_menu_price("치즈케이크")     → "6500원"
3) create_order("치즈케이크", 2)    → "주문번호 A-102, 합계 13000원"
4) Claude → "치즈케이크 2개 주문이 완료됐습니다. 합계 13,000원입니다."`,
    },
    note: '모든 일에 에이전트가 필요한 것은 아니다. 순서가 정해진 일("검색 → 답변")은 고정 워크플로가 더 싸고 예측하기 쉽다.',
  },

  '프롬프트 캐싱': {
    kind: 'claude',
    title: '긴 메뉴판과 규정을 매번 다시 처리하지 않기',
    situation: '카페 메뉴가 200개이고 규정이 길면, 손님이 질문할 때마다 같은 긴 안내문을 다시 보내게 된다. 반복되는 앞부분을 캐시해 두면 두 번째 질문부터 비용과 시간이 줄어든다.',
    steps: [
      '변하지 않는 긴 안내문을 system 앞쪽에 두고 cache_control 을 붙인다',
      '질문처럼 매번 바뀌는 부분은 뒤(user 메시지)에 둔다 (앞부분이 글자 단위로 같아야 캐시가 맞는다)',
      '응답의 usage 로 캐시가 맞았는지 확인한다',
    ],
    blocks: [
      {
        lang: 'java',
        title: 'system 에 cache_control 붙이기 (Java, Anthropic SDK)',
        code: String.raw`import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.TextBlockParam;
import java.util.List;

MessageCreateParams params = MessageCreateParams.builder()
    .model("${MODEL}")
    .maxTokens(500L)
    .systemOfTextBlockParams(List.of(
        TextBlockParam.builder()
            .text(longCafeGuide)                              // 바뀌지 않는 긴 안내문
            .cacheControl(CacheControlEphemeral.builder().build())
            .build()))
    .addUserMessage(question)                                  // 매번 바뀌는 질문은 뒤에
    .build();

Message response = client.messages().create(params);
System.out.println("캐시에 씀: " + response.usage().cacheCreationInputTokens());
System.out.println("캐시에서 읽음: " + response.usage().cacheReadInputTokens());`,
      },
    ],
    output: {
      title: '같은 안내문으로 두 번 호출 (예시)',
      text: String.raw`1번째  캐시에 씀: (안내문 토큰 수)   캐시에서 읽음: 0
2번째  캐시에 씀: 0                  캐시에서 읽음: (안내문 토큰 수)   ← 적중`,
    },
    note: '캐시하려면 앞부분이 모델별 최소 길이(수백~수천 토큰) 이상이어야 한다. 위 카페 안내 문서(몇 줄)는 너무 짧아서 캐시되지 않는다. 메뉴와 규정이 길 때 의미가 있다. 캐시에서 읽은 입력은 더 싸게 청구된다(단가는 공식 문서 확인). 이미지 캐싱의 "같은 요청은 다시 계산하지 않는다"와 같은 발상이다.',
    link: { path: '/image-cache', label: '이미지 캐싱 실험(같은 발상) 보기' },
  },

  '구조화 출력': {
    kind: 'claude',
    title: '주문 문장을 JSON 으로 받기',
    situation: '손님이 "라떼 따뜻하게 두 잔이랑 치즈케이크 하나"라고 말한다. 주문 시스템은 문장을 읽지 못하므로 {메뉴, 수량, 온도} 같은 정해진 형식으로 받아야 한다.',
    steps: [
      '받고 싶은 형태를 Java record 로 정의한다',
      'SDK 가 record 에서 JSON 스키마를 만들어 Claude 에게 형식을 강제하고, 응답을 record 로 바꿔 준다',
      '문자열을 직접 파싱하지 않는다',
    ],
    blocks: [
      {
        lang: 'java',
        title: '주문을 record 로 받기 (Java, Anthropic SDK)',
        code: String.raw`import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import java.util.List;

record OrderLine(String menu, int quantity, String temperature) {}
record Order(List<OrderLine> lines) {}

StructuredMessageCreateParams<Order> params = MessageCreateParams.builder()
    .model("${MODEL}")
    .maxTokens(1000L)
    .outputConfig(Order.class)        // record 에서 스키마를 만든다
    .addUserMessage("라떼 따뜻하게 두 잔이랑 치즈케이크 하나")
    .build();

client.messages().create(params).content().stream()
    .flatMap(block -> block.text().stream())
    .forEach(typed -> {
        for (OrderLine line : typed.text().lines()) {   // typed.text() 는 Order 타입
            System.out.println(line.menu() + " x" + line.quantity());
        }
    });`,
      },
    ],
    output: { title: '예상 출력 (실제 응답은 달라질 수 있다)', text: '카페라떼 x2\n치즈케이크 x1' },
    note: '문장을 코드가 읽을 수 있는 값으로 바꾸는 단계다. 에이전트의 "주문 생성" 도구 인자도 같은 방식으로 받는다.',
  },
}
