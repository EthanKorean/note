import { createRouter, createWebHashHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import BulkInsertView from './views/BulkInsertView.vue'
import ImageCacheView from './views/ImageCacheView.vue'
import AiTermsView from './views/AiTermsView.vue'
import RagView from './views/RagView.vue'
import SuikaView from './views/SuikaView.vue'
import CatchView from './views/CatchView.vue'

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: HomeView, meta: { title: '홈' } },
    { path: '/bulk-insert', component: BulkInsertView, meta: { title: '대용량 적재' } },
    { path: '/image-cache', component: ImageCacheView, meta: { title: '이미지 캐싱' } },
    { path: '/ai-terms', component: AiTermsView, meta: { title: 'AI 용어 정리' } },
    { path: '/rag', component: RagView, meta: { title: 'RAG 검색' } },
    { path: '/suika', component: SuikaView, meta: { title: '수박 게임' } },
    { path: '/catch', component: CatchView, meta: { title: '캐치마인드' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.afterEach((to) => {
  const t = to.meta.title as string | undefined
  document.title = t && to.path !== '/' ? `${t} · Note` : 'Note'
})
