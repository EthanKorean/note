<script setup lang="ts">
import { ref } from 'vue'
import { learningOrder, statusLabel, termGroups, whyItems } from '../aiTerms'
import { demos } from '../aiDemos'
import Card from '../components/Card.vue'
import ExampleModal from '../components/ExampleModal.vue'
import PageNav from '../components/PageNav.vue'

const coreCount = termGroups.reduce((n, g) => n + g.terms.filter((t) => t.core).length, 0)
const totalCount = termGroups.reduce((n, g) => n + g.terms.length, 0)
/** ★ 핵심 용어만 보기. 처음에는 켜 둔다(핵심 용어가 없는 묶음도 카드는 남겨 두어 오른쪽 목차 이동이 깨지지 않는다). */
const onlyCore = ref(true)

const demoCount = Object.keys(demos).length
/** 지금 모달에 열려 있는 용어 이름. 없으면 닫힘. */
const openDemo = ref<string | null>(null)

const sections = [
  { id: 'card-intro', label: '소개' },
  { id: 'card-why', label: '왜 필요한가' },
  ...termGroups.map((g) => ({ id: g.id, label: g.title.split(':')[0] })),
  { id: 'card-order', label: '해 보는 순서' },
]
</script>

<template>
  <div class="with-toc">
    <div class="page">
      <header>
        <h1>AI 용어 정리</h1>
        <p class="muted">AX 개발을 하면서 생기는 새 용어를 정리하고, 하나씩 직접 만들어 보며 익힙니다.</p>
      </header>

      <Card id="card-intro" title="소개">
        <table class="kv">
          <tbody>
            <tr><th scope="row">배경</th><td>AX 개발을 하면서 모르는 신조어가 많이 생기고 있다</td></tr>
            <tr><th scope="row">목적</th><td>용어를 공부하고, 누구나 아는 카페 하나로 쉽게 설명하고, 이 프로젝트에서 직접 만들어 본다</td></tr>
            <tr><th scope="row">지금</th><td>용어를 정리하고, 상태가 "진행 중"인 것은 이 프로젝트에서 이미 만들었다(AI → RAG 검색 화면)</td></tr>
            <tr>
              <th scope="row">상태</th>
              <td>
                <span class="tag st-doing">진행 중</span> 이 프로젝트에서 만들었거나 만드는 중 ·
                <span class="tag st-next">다음</span> 차례 대기(답변 생성은 API 키 필요) ·
                <span class="tag st-idea">나중에</span> 아이디어
              </td>
            </tr>
            <tr><th scope="row">기록</th><td>측정 결과와 느낀 점은 docs/ax/rag.md에 남겼다</td></tr>
            <tr><th scope="row">쉬운 예시</th><td>모든 용어를 해든 카페 안내 챗봇 하나로 설명한다. "쉬운 예제 보기" 버튼이 있는 용어 {{ demoCount }}개는 코드까지 볼 수 있다(Claude 예제는 API 키가 없어 실행해 보지 않았다)</td></tr>
            <tr><th scope="row">프로젝트에서는</th><td>카페 설명과 세부가 다를 때만 적는다. 이 노트 프로젝트에서 실제로 쓴 모델, 측정 결과, 아직 안 한 것이다. RAG 검색 화면과 이어진다</td></tr>
            <tr><th scope="row"><span class="star">★ 표시</span></th><td>먼저 알아야 할 핵심 용어 {{ coreCount }}개(전체 {{ totalCount }}개). 나머지는 필요할 때 찾아보는 용도다</td></tr>
          </tbody>
        </table>
        <template #footer>
          <div class="foot-grid">
            <div class="foot-checks only-core">
              <label><input v-model="onlyCore" type="checkbox" /> <span class="star">★</span> 핵심 용어만 보기</label>
            </div>
          </div>
        </template>
      </Card>

      <Card id="card-why" title="왜 필요한가">
        <table class="kv">
          <tbody>
            <tr v-for="w in whyItems" :key="w.label"><th scope="row">{{ w.label }}</th><td>{{ w.text }}</td></tr>
          </tbody>
        </table>
      </Card>

      <Card v-for="g in termGroups" :id="g.id" :key="g.id" :title="g.title">
        <ul class="legend group-intro">
          <li>{{ g.intro }}</li>
        </ul>
        <p v-if="onlyCore && !g.terms.some((t) => t.core)" class="muted no-core">이 묶음에는 ★ 핵심 용어가 없습니다. 전부 보려면 위 소개 카드에서 "핵심 용어만 보기"를 끄세요.</p>
        <div v-for="t in onlyCore ? g.terms.filter((x) => x.core) : g.terms" :key="t.name" class="term">
          <div class="term-head">
            <h3>
              <span v-if="t.core" class="star" title="먼저 알아야 할 핵심 용어">★</span>
              {{ t.name }}
              <small v-if="t.en" class="en">{{ t.en }}</small>
            </h3>
            <div class="head-right">
              <button v-if="demos[t.name]" type="button" class="ghost" @click="openDemo = t.name">쉬운 예제 보기</button>
              <span class="tag" :class="`st-${t.status}`">{{ statusLabel[t.status] }}</span>
            </div>
          </div>
          <table class="kv">
            <tbody>
              <tr><th scope="row">뜻</th><td>{{ t.what }}</td></tr>
              <tr><th scope="row">왜 필요한가</th><td>{{ t.why }}</td></tr>
              <tr><th scope="row">쉬운 예시</th><td>{{ t.example }}</td></tr>
              <tr v-if="t.project"><th scope="row">프로젝트에서는</th><td>{{ t.project }}</td></tr>
              <tr v-if="t.note"><th scope="row">알아 둘 점</th><td>{{ t.note }}</td></tr>
            </tbody>
          </table>
        </div>
      </Card>

      <Card id="card-order" title="해 보는 순서">
        <table class="kv">
          <tbody>
            <tr v-for="o in learningOrder" :key="o.step">
              <th scope="row">{{ o.step }}</th>
              <td>{{ o.detail }} <span class="tag" :class="`st-${o.status}`">{{ statusLabel[o.status] }}</span></td>
            </tr>
          </tbody>
        </table>
      </Card>
    </div>
    <PageNav title="AI 용어 정리" :items="sections" />
    <ExampleModal v-if="openDemo && demos[openDemo]" :term="openDemo" :demo="demos[openDemo]" @close="openDemo = null" />
  </div>
</template>

<style scoped>
.group-intro { margin: 0 0 14px; }
.star { color: var(--warn-text); }
.head-right { display: flex; align-items: center; gap: 8px; flex: none; }
.only-core { padding-top: 0; }
.no-core { margin: 0; }
.term + .term { margin-top: 18px; }
.term .kv { margin-top: 8px; }
.term-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.term-head h3 { margin: 0; font-size: 1rem; }
.en { margin-left: 6px; color: var(--muted); font-weight: 400; font-size: 1rem; }
.st-doing { background: var(--accent); color: var(--accent-text); }
.st-next { background: var(--tag-bg); color: var(--text); border: 1px solid var(--border); }
.st-idea { background: transparent; color: var(--muted); border: 1px dashed var(--border); }
</style>
