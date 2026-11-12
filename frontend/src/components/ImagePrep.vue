<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useImageStore } from '../stores/image'
import { useWsStore } from '../stores/ws'
import Card from './Card.vue'

const store = useImageStore()
const { status, seeding, seedSource, running } = storeToRefs(store)
const count = ref(1000)
const { lastLog } = storeToRefs(useWsStore())

onMounted(() => store.refreshStatus())

const mb = (b: number) => `${(b / 1024 / 1024).toFixed(1)} MB`
</script>

<template>
  <Card id="card-prep" title="이미지 준비">
    <ul class="legend">
      <li><b>이미지 만들기</b> 서버가 번호와 색으로 직접 그립니다(빠르고 인터넷이 필요 없음, 파일이 작아 리사이징 비용이 실제보다 작음)</li>
      <li><b>샘플 사진 받기</b> 무료 공개 사진 사이트(picsum.photos)에서 1,200x900 사진을 받아 옵니다. 파일이 약 200KB로 커서 실제 쇼핑몰에 더 가깝고, 인터넷이 필요합니다. 같은 번호는 항상 같은 사진이고, 이미 있는 이미지도 사진으로 바꿉니다. 먼저 100개쯤 받아서 실험 화면의 상품 수를 맞춰 보세요</li>
      <li>원본 1,200x900과 웹 800x600, 앱 400x300, 썸네일 150x113 네 가지가 만들어집니다</li>
      <li v-if="status">
        지금 <b>{{ status.count.toLocaleString() }}개</b> 상품이 있습니다
        <template v-if="status.count">
          (웹 {{ mb(status.bytesBySize.web) }} · 앱 {{ mb(status.bytesBySize.app) }} · 썸네일 {{ mb(status.bytesBySize.thumb) }})
        </template>
      </li>
      <li class="muted">이미 있는 번호는 건너뛰므로 개수를 늘려서 다시 눌러도 됩니다. 저장 위치: {{ status?.dir }}</li>
    </ul>

    <div v-if="seeding" class="loading" role="status" aria-live="polite">
      <div class="spinner" aria-hidden="true" />
      <p class="loading-title">{{ seedSource === 'web' ? '샘플 사진 받는 중' : '이미지 만드는 중' }}</p>
      <p class="muted">{{ lastLog?.message ?? (seedSource === 'web' ? '100개에 30초~1분 정도 걸립니다' : '1,000개에 10초 정도 걸립니다') }}</p>
    </div>

    <template #footer>
      <div class="foot-grid">
        <label class="foot-field">
          <span>상품 수</span>
          <input v-model.number="count" type="number" min="1" max="100000" :disabled="seeding || running" />
          <small>1 ~ 100,000</small>
        </label>
        <button class="primary" :class="{ busy: seeding && seedSource === 'draw' }" :disabled="seeding || running" @click="store.seed(count)">
          <span v-if="seeding && seedSource === 'draw'" class="btn-spinner" aria-hidden="true" />
          {{ seeding && seedSource === 'draw' ? '만드는 중…' : '이미지 만들기' }}
        </button>
        <button class="primary" :class="{ busy: seeding && seedSource === 'web' }" :disabled="seeding || running" @click="store.seed(count, true, 'web')">
          <span v-if="seeding && seedSource === 'web'" class="btn-spinner" aria-hidden="true" />
          {{ seeding && seedSource === 'web' ? '받는 중…' : '샘플 사진 받기' }}
        </button>
      </div>
    </template>
  </Card>
</template>
