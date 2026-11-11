export interface CodeBlock {
  lang: 'sql' | 'java' | 'xml' | 'gradle' | 'yaml' | 'typescript'
  title: string
  code: string
}

/** 방식별 장단점과 느낀점(이번 측정 기준). 화면에서는 불릿 목록으로 보여 준다. */
export interface MethodNotes {
  pros: string[]
  cons: string[]
  feel: string[]
  /** 트랜잭션을 어떻게 나누고 실패하면 어떻게 되는지 */
  tx: string[]
}

export interface MethodSample {
  value: string
  label: string
  summary: string
  blocks: CodeBlock[]
  notes?: MethodNotes
}

const loadDataSql = (local: string, path: string) =>`LOAD DATA ${local}INFILE '${path}'
INTO TABLE rnaddr_kor
CHARACTER SET euckr                      -- MariaDB에는 cp949가 없음
FIELDS TERMINATED BY '|' ESCAPED BY ''
LINES TERMINATED BY '\\n'
(@c1, @c2, ..., @c23, @dummy)            -- 행 끝의 여분 '|' 는 @dummy 로 버림
SET mng_no   = NULLIF(@c1, ''),          -- 빈 값은 NULL
    bjd_code = NULLIF(@c2, ''),
    ...`

const baseSamples: MethodSample[] = [
  {value:"loaddata-local",label:"LOAD DATA LOCAL INFILE",summary:"앱이 파일을 읽어 DB 연결(JDBC)로 흘려보냅니다. 파일이 DB 서버에 없어도 됩니다. DB 서버가 파일을 직접 읽는 방식(LOAD DATA INFILE)과 같은 머신에서는 속도가 비슷했습니다.",blocks:[{lang:"sql",title:"SQL · 실행되는 문장",code:loadDataSql("LOCAL ","/data/202610_juso.txt")},{lang:"java",title:"Java · loader/LoadDataLoader",code:`// 한 문장으로 전체 적재. 파일은 앱(JDBC 드라이버)이 읽어서 서버로 보낸다.
try (Statement st = conn.createStatement()) {
    st.executeUpdate(sql);
}`}]},
  {value:"parallel",label:"병렬 멀티 로우 INSERT",summary:"읽기 스레드 1개가 청크를 큐에 넣고, 쓰기 스레드 N개가 각자 커넥션으로 멀티 로우 INSERT 합니다. 순서가 섞여 PK 삽입 이점은 줄지만 CPU와 디스크를 더 씁니다.",blocks:[{lang:"java",title:"Java · loader/ParallelLoader",code:`BlockingQueue<List<String[]>> queue = new ArrayBlockingQueue<>(threads * 2);
ExecutorService pool = Executors.newFixedThreadPool(threads);

for (int i = 0; i < threads; i++) {            // 워커: 각자 커넥션
    pool.submit(() -> {
        try (Connection conn = newConnection()) {
            conn.setAutoCommit(false);
            while (true) {
                List<String[]> chunk = queue.take();
                if (chunk == POISON) return;
                MultiRowLoader.insertChunk(conn, table, chunk);
                conn.commit();
            }
        }
    });
}
Rows.forEachChunk(file, batchSize, queue::put); // 생산자: 파일 읽기 1개
for (int i = 0; i < threads; i++) queue.put(POISON);`}]},
  {value:"mybatis-batch",label:"MyBatis batch",summary:'MyBatis ExecutorType.BATCH 세션에서 행마다 매퍼를 호출합니다. SQL은 XML에 두고, 커밋 직전에 JDBC 배치로 묶여 전송됩니다. ORM 없이 SQL을 직접 쓰는 매퍼 방식입니다.',blocks:[{lang:"xml",title:"XML · mapper/JusoMapper.xml (컬럼 23개를 직접 적는다)",code:`<insert id="insertOne">
    INSERT INTO \${table} (mng_no, bjd_code, ..., bld_nm_sigungu)
    VALUES (#{row[0]}, #{row[1]}, ..., #{row[22]})
</insert>`},{lang:"java",title:"Java · loader/MyBatisBatchLoader",code:`SqlSessionTemplate batch = new SqlSessionTemplate(factory, ExecutorType.BATCH);
JusoMapper mapper = batch.getMapper(JusoMapper.class);

Rows.forEachChunk(file, batchSize, chunk -> tx.executeWithoutResult(s -> {
    for (String[] row : chunk) mapper.insertOne(table, row);  // 커밋 시 배치 전송
}));`}]},
  {value:"mybatis-parallel-batch",label:"MyBatis 병렬 batch",summary:"읽기 스레드 1개가 청크를 큐에 넣고, 쓰기 스레드 N개가 각자 트랜잭션(커넥션)으로 MyBatis BATCH 실행기를 씁니다. SqlSessionTemplate 은 스레드 안전해서 스레드마다 전용 세션이 따로 열립니다.",blocks:[{lang:"java",title:"Java · loader/MyBatisParallelBatchLoader",code:`JusoMapper mapper = new SqlSessionTemplate(factory, ExecutorType.BATCH)
        .getMapper(JusoMapper.class);

ParallelRunner.run(ctx, source -> {            // 워커 N개가 같은 코드를 실행
    List<String[]> chunk;
    while ((chunk = source.next()) != null) {
        tx.executeWithoutResult(s -> {         // 청크마다 트랜잭션 = 커넥션 1개
            for (String[] row : chunk) mapper.insertOne(table, row);
        });                                    // 커밋 시 배치 전송
    }
});`}]},
  {value:"mybatis-parallel-foreach",label:"MyBatis 병렬 foreach",summary:"쓰기 스레드 N개가 각자 트랜잭션으로 <foreach> 멀티 로우 INSERT 를 실행합니다. 단일 스레드 foreach 가 느린 이유(Map 변환·SQL 조립)가 CPU 작업이라 스레드를 나누면 효과가 큰지 보는 방식입니다.",blocks:[{lang:"java",title:"Java · loader/MyBatisParallelForeachLoader",code:`ParallelRunner.run(ctx, source -> {            // 워커 N개가 같은 코드를 실행
    List<String[]> chunk;
    while ((chunk = source.next()) != null) {
        tx.executeWithoutResult(s ->
            mapper.insertMulti(table, toMaps(chunk)));
    }
});`}]},
  {
    value: 'jpa-parallel',
    label: 'JPA 병렬 saveAll',
    summary:
      'JPA saveAll 을 쓰기 스레드 N개가 각자 트랜잭션(EntityManager/커넥션)으로 실행합니다. 행마다 SELECT 하는 merge 는 그대로지만 DB 왕복이 겹쳐서 얼마나 줄어드는지 보는 방식입니다.',
    blocks: [
      {
        lang: 'java',
        title: 'Java · loader/JpaParallelLoader',
        code: `ParallelRunner.run(ctx, source -> {            // 워커 N개가 같은 코드를 실행
    List<String[]> chunk;
    while ((chunk = source.next()) != null) {
        tx.executeWithoutResult(s ->           // 청크마다 트랜잭션 = 영속성 컨텍스트 1개
            repository.saveAll(chunk.stream().map(RnaddrKor::from).toList()));
    }
});`,
      },
    ],
  },
]

/** 방식별 의존성(build.gradle)과 import. 코드 예제 앞에 붙여 어느 패키지에서 가져오는지 보여 준다. */
const MARIADB = "    runtimeOnly 'org.mariadb.jdbc:mariadb-java-client'"
const setup: Record<string, { gradle: string; imports: string; xml?: { title: string; code: string }; before?: CodeBlock[] }> = {
  'loaddata-local': {
    gradle: `dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'   // DataSource, JdbcTemplate
${MARIADB}
}`,
    imports: `import java.sql.Connection;          // JDK
import java.sql.Statement;           // JDK`,
  },
  parallel: {
    gradle: `dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
${MARIADB}
}`,
    imports: `import java.sql.Connection;                       // JDK
import java.util.List;                            // JDK
import java.util.concurrent.ArrayBlockingQueue;   // JDK
import java.util.concurrent.BlockingQueue;        // JDK
import java.util.concurrent.ExecutorService;      // JDK
import java.util.concurrent.Executors;            // JDK
import com.ethan.note.juso.loader.MultiRowLoader; // 이 프로젝트
import com.ethan.note.juso.loader.Rows;           // 이 프로젝트`,
  },
  'mybatis-batch': {
    gradle: `dependencies {
    implementation 'org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.3'  // MyBatis + 스프링 연동
${MARIADB}
}`,
    imports: `import org.apache.ibatis.session.ExecutorType;      // mybatis
import org.apache.ibatis.session.SqlSessionFactory;  // mybatis
import org.mybatis.spring.SqlSessionTemplate;        // mybatis-spring
import org.springframework.transaction.support.TransactionTemplate;  // spring-tx
import com.ethan.note.juso.mybatis.JusoMapper;       // 이 프로젝트
import com.ethan.note.juso.loader.Rows;              // 이 프로젝트`,
  },
  'mybatis-parallel-batch': {
    gradle: `dependencies {
    implementation 'org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.3'
${MARIADB}
}`,
    imports: `import java.util.List;                            // JDK
import org.apache.ibatis.session.ExecutorType;       // mybatis
import org.apache.ibatis.session.SqlSessionFactory;  // mybatis
import org.mybatis.spring.SqlSessionTemplate;        // mybatis-spring
import org.springframework.transaction.support.TransactionTemplate;  // spring-tx
import com.ethan.note.juso.mybatis.JusoMapper;       // 이 프로젝트
import com.ethan.note.juso.loader.ParallelRunner;    // 이 프로젝트`,
    xml: {
      title: 'XML · mapper/JusoMapper.xml (컬럼 23개를 직접 적는다)',
      code: `<insert id="insertOne">
    INSERT INTO \${table} (mng_no, bjd_code, ..., bld_nm_sigungu)
    VALUES (#{row[0]}, #{row[1]}, ..., #{row[22]})
</insert>`,
    },
  },
  'mybatis-parallel-foreach': {
    gradle: `dependencies {
    implementation 'org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.3'
${MARIADB}
}`,
    imports: `import java.util.List;                            // JDK
import org.springframework.transaction.support.TransactionTemplate;  // spring-tx
import com.ethan.note.juso.mybatis.JusoMapper;       // 이 프로젝트
import com.ethan.note.juso.loader.ParallelRunner;    // 이 프로젝트`,
    xml: {
      title: 'XML · mapper/JusoMapper.xml (행을 Map으로 넘겨 #{r.컬럼명})',
      code: `<insert id="insertMulti">
    INSERT INTO \${table} (mng_no, ..., bld_nm_sigungu) VALUES
    <foreach collection="rows" item="r" separator=",">
        (#{r.mng_no}, #{r.bjd_code}, ..., #{r.bld_nm_sigungu})
    </foreach>
</insert>`,
    },
  },
  'jpa-parallel': {
    gradle: `dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'  // JPA + Hibernate + Spring Data
${MARIADB}
}`,
    imports: `import java.util.List;                            // JDK
import org.springframework.transaction.support.TransactionTemplate;  // spring-tx
import com.ethan.note.juso.jpa.RnaddrKor;            // 이 프로젝트 (엔티티)
import com.ethan.note.juso.jpa.RnaddrKorRepository;  // 이 프로젝트 (JpaRepository)
import com.ethan.note.juso.loader.ParallelRunner;    // 이 프로젝트`,
    before: [
      {
        lang: 'java',
        title: 'Java · jpa/RnaddrKor, RnaddrKorRepository',
        code: `import jakarta.persistence.Column;                 // jakarta.persistence-api (JPA 표준)
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;  // spring-data-jpa

@Entity @Table(name = "rnaddr_kor")
public class RnaddrKor {
    @Id @Column(name = "mng_no") private String mngNo;   // PK 직접 지정 → isNew=false → merge
    @Column(name = "bjd_code")   private String bjdCode;
    // ... 컬럼 23개를 필드로 선언 (Lombok @Getter @Setter @NoArgsConstructor)

    public static RnaddrKor from(String[] r) { ... }       // 파일 한 줄(23개 값) → 엔티티
}

public interface RnaddrKorRepository extends JpaRepository<RnaddrKor, String> {
}`,
      },
      {
        lang: 'yaml',
        title: 'YAML · application.yml (하이버네이트 배치 설정)',
        code: `spring:
  jpa:
    properties:
      hibernate:
        jdbc.batch_size: 1000     # INSERT 를 1,000개씩 묶어 전송 (청크를 키워도 이 값에 묶인다)
        order_inserts: true`,
      },
    ],
  },
}

/** 방식별 장단점·느낀점. 수치는 642만 행, 인덱스 포함, 방식당 1회 측정한 총 소요(행 수 세기~검증)다. */
const notes: Record<string, MethodNotes> = {
  'loaddata-local': {
    pros: ['문장 1개로 끝나 코드가 가장 짧다', '파일을 가공 없이 넣을 때 가장 단순하다', '파일이 DB 서버에 없어도 된다(LOCAL)'],
    cons: [
      '1분 34초로 병렬 방식(55초~1분 9초)보다 느렸다 — 연결 하나로 쓰기 때문',
      '행 단위 가공·검증이 어렵고, 빈 값·인코딩 처리를 SQL에 적어야 한다(NULLIF, euckr)',
      '중복 행을 조용히 건너뛸 수 있어 건수 검증이 필요하다',
    ],
    feel: [
      '"LOAD DATA가 가장 빠르다"는 통념과 달리 이 환경에서는 병렬 쓰기에 졌다',
      '그래도 코드가 거의 없다는 점이 가장 큰 장점이라, 가공 없는 파일을 한 번 넣는 작업의 기본값으로 좋다',
    ],
    tx: [
      '오토커밋 상태에서 문장 1개 = 트랜잭션 1개다. 642만 행이 한 번에 커밋되거나 한 번에 취소된다(InnoDB 기준)',
      '중간 커밋 단위를 정할 수 없고, 한 트랜잭션이 커서 undo 로그가 많이 쌓인다',
      'LOCAL은 서버가 전송을 중간에 멈출 수 없어서 중복 키 같은 오류가 경고로 바뀌고 행만 건너뛴다 — 실패로 끝나지 않으므로 적재 후 COUNT 검증이 필수다',
      '이 프로젝트에서 실패 상황을 일부러 만들어 롤백을 확인해 보지는 않았다(일반적으로 알려진 동작)',
    ],
  },
  parallel: {
    pros: ['이번 측정 최고 속도: 배치 2,000에서 55초', '쓰기 문장과 커넥션을 직접 통제한다'],
    cons: [
      '스레드·큐·커넥션 코드를 직접 짜고 유지해야 한다',
      'SQL 문자열을 직접 조립하고, 바인드 변수 제한(2,849행) 때문에 배치 크기를 키울 수 없다',
      '순서가 섞여 들어가 PK 삽입 이점이 줄어든다',
    ],
    feel: [
      '실무에서 프레임워크 없이 JDBC만 쓰는 일은 드물어서, 쓰기보다 "가장 빠른 기준선"으로 읽는 것이 맞다',
      'MyBatis 병렬 batch가 1분 00초로 5초(약 9%) 차이밖에 안 나서, 직접 짤 이유가 작다',
    ],
    tx: [
      '워커마다 커넥션을 따로 잡고 오토커밋을 끈 뒤, 청크(배치 크기)마다 직접 commit() 한다',
      '트랜잭션이 커넥션별로 나뉘어 전체가 하나로 묶이지 않는다 — 중간에 실패하면 이미 커밋된 청크는 남는다',
      '이 프로젝트는 실패를 기록하고 다음 실행에서 테이블을 비우는 방식으로 처리한다(롤백이 아니라 재적재)',
      'commit/rollback 코드를 전부 직접 관리해야 한다',
    ],
  },
  'mybatis-batch': {
    pros: ['코드가 단순하다(트랜잭션 + 행마다 매퍼 호출)', 'SQL이 XML에 분리된다', '단일 스레드라 파일 순서가 보존된다'],
    cons: [
      '2분 57초~3분 13초로 병렬 batch(1분 안팎)의 약 3배',
      '컬럼 23개를 XML에 직접 나열해야 한다',
    ],
    feel: [
      '배치를 1,000에서 1만 이상으로 키우면 16초 줄지만, 병목은 단일 스레드 쓰기라 효과가 제한적이다',
      '병렬로 바꾸는 쪽이 배치 크기 조정보다 훨씬 효과가 컸다',
    ],
    tx: [
      'TransactionTemplate으로 청크마다 트랜잭션을 열고 끝나면 커밋한다. 오류가 나면 그 청크만 롤백된다',
      'BATCH 실행기는 커밋 직전에 쌓아 둔 INSERT를 한 번에 전송한다(flush)',
      '청크 단위로 커밋되므로 중간에 실패하면 앞 청크는 남는다 — 전체를 한 트랜잭션으로 묶으면 롤백은 쉬워지지만 undo 로그가 커진다',
      '스프링이 커넥션·커밋·롤백을 관리해서 직접 짜는 코드가 없다',
    ],
  },
  'mybatis-parallel-batch': {
    pros: [
      '프레임워크 안에서 직접 JDBC에 가까운 속도: 1분 00초~1분 09초',
      '배치 크기 1,000~3만 모두 거의 같다 — 크기를 고민할 필요가 적다',
      '바인드 변수 제한이 없어 크기를 자유롭게 정할 수 있다',
    ],
    cons: [
      '병렬 골격(큐, 워커)을 직접 만들어야 한다',
      '컬럼 23개를 XML에 직접 나열해야 한다',
      '스레드 수는 커넥션 풀 크기(12)를 넘길 수 없다',
    ],
    feel: [
      '이번 비교에서 실무에 가장 쓸 만한 선택: 속도와 코드 관리의 균형이 가장 좋았다',
      'MyBatis를 이미 쓰는 프로젝트라면 대량 적재만 이 방식으로 분리하면 된다',
    ],
    tx: [
      '워커 스레드마다 TransactionTemplate으로 자기 트랜잭션(=자기 커넥션)을 열고 청크마다 커밋한다',
      '트랜잭션은 스레드에 묶여 있어 스레드 간에 공유되지 않는다. SqlSessionTemplate은 스레드 안전해서 트랜잭션별로 전용 세션을 꺼낸다',
      '워커별로 커밋되므로 한 워커가 실패하면 나머지는 중단시키지만 이미 커밋된 청크는 남는다',
      '커넥션이 워커 수만큼 필요하다(풀 크기 12 이하로)',
    ],
  },
  'mybatis-parallel-foreach': {
    pros: ['문장 하나에 여러 행이라 DB 왕복이 적다', 'SQL을 XML에서 관리한다'],
    cons: [
      '2분 04초~2분 08초로 병렬 batch의 약 2배',
      '행을 Map으로 바꾸고 큰 SQL을 매번 만드는 비용이 크다',
      '한 문장 행 수 상한(2,849)이 있고, foreach 안에서는 배열 인덱스를 쓸 수 없다',
    ],
    feel: [
      '직접 JDBC 멀티 로우(55초)와 같은 구조인데 2배 넘게 걸려, 프레임워크 오버헤드가 가장 크게 드러난 지점이다',
      '대량 적재에는 foreach보다 batch가 낫다',
    ],
    tx: [
      '워커마다 TransactionTemplate으로 청크 하나를 한 트랜잭션에 넣는다. 문장 하나가 곧 한 트랜잭션이 되는 구조다',
      '오류가 나면 그 청크(문장 하나)만 롤백된다',
      '전체 롤백이 아니라 청크 단위라는 점은 병렬 batch와 같다',
    ],
  },
  'jpa-parallel': {
    pros: [
      '엔티티·리포지토리를 그대로 써서 도메인 규칙과 검증을 연결하기 쉽다',
      '병렬로 바꾸면 단일 실행 대비 크게 줄었다(이전 단일 측정 14분 15초 → 4분 반)',
    ],
    cons: [
      '가장 느리다: 4분 21초~4분 30초로 직접 JDBC의 약 5배',
      'PK를 직접 지정하면 merge가 되어 행마다 SELECT가 먼저 나간다',
      '배치 크기를 바꿔도 결과가 같다 — 하이버네이트 jdbc.batch_size(1,000)에 묶여 있다',
      '영속성 컨텍스트 메모리를 쓰고, unique/FK 검사를 끌 수 없다',
    ],
    feel: [
      '대량 적재용이 아니라 도메인 데이터 저장용이다. 대량 적재는 JDBC·MyBatis·LOAD DATA로 우회하는 것이 맞다',
      '병렬화는 도움이 되지만, 행마다 SELECT가 나가는 근본 비용은 그대로다',
    ],
    tx: [
      '워커마다 TransactionTemplate으로 청크 하나(saveAll)를 한 트랜잭션에서 처리한다. 트랜잭션마다 영속성 컨텍스트가 새로 생긴다',
      'INSERT는 커밋(flush) 시점에 하이버네이트가 jdbc.batch_size(1,000)개씩 묶어 보낸다',
      '오류가 나면 그 트랜잭션만 롤백되고 영속성 컨텍스트도 버려진다. 앞서 커밋된 청크는 남는다',
      'JPA가 변경 감지·flush 시점을 관리해서 편하지만, 어느 시점에 SQL이 나가는지 직접 보기는 어렵다',
    ],
  },
}

export const methodSamples: MethodSample[] = baseSamples.map((m) => {
  const x = setup[m.value]
  if (!x) return { ...m, notes: notes[m.value] }
  let imported = false
  const blocks = m.blocks.map((b) => {
    if (b.lang !== 'java' || imported) return b
    imported = true
    return { ...b, code: `${x.imports}\n\n${b.code}` }
  })
  const xmlBlock: CodeBlock[] = x.xml ? [{ lang: 'xml', ...x.xml }] : []
  const gradle: CodeBlock = { lang: 'gradle', title: 'Gradle · build.gradle 의존성', code: x.gradle }
  return { ...m, notes: notes[m.value], blocks: [gradle, ...xmlBlock, ...(x.before ?? []), ...blocks] }
})
