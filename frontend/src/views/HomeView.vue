<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { menu } from '../menu'

const groups = menu.filter((g) => g.title)
</script>

<template>
  <div class="page">
    <header>
      <h1>Note</h1>
      <p class="muted">여러 가지를 직접 돌려 보고 기록하는 스터디 프로젝트입니다. 왼쪽 메뉴에서 테스트를 고르세요.</p>
    </header>

    <section v-for="g in groups" :key="g.title" class="card">
      <h2>{{ g.title }}</h2>
      <ul class="tests">
        <li v-for="i in g.items" :key="i.path">
          <RouterLink v-if="!i.soon" :to="i.path">{{ i.label }}</RouterLink>
          <span v-else class="muted">{{ i.label }} <small>준비 중</small></span>
          <small v-if="i.desc" class="muted">{{ i.desc }}</small>
        </li>
      </ul>
    </section>
  </div>
</template>
