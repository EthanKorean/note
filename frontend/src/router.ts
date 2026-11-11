import { createRouter, createWebHashHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import BulkInsertView from './views/BulkInsertView.vue'

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: HomeView, meta: { title: '홈' } },
    { path: '/bulk-insert', component: BulkInsertView, meta: { title: '대용량 적재' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.afterEach((to) => {
  const t = to.meta.title as string | undefined
  document.title = t && to.path !== '/' ? `${t} · Note` : 'Note'
})
