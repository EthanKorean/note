<script setup lang="ts">
import { computed } from 'vue'
import hljs from 'highlight.js/lib/core'
import java from 'highlight.js/lib/languages/java'
import xml from 'highlight.js/lib/languages/xml'
import gradle from 'highlight.js/lib/languages/gradle'
import yaml from 'highlight.js/lib/languages/yaml'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import type { CodeBlock } from '../methodSamples'

hljs.registerLanguage('java', java)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('gradle', gradle)
hljs.registerLanguage('yaml', yaml)
hljs.registerLanguage('typescript', typescript)

const props = defineProps<{ block: CodeBlock }>()

// 코드는 이 프로젝트에 고정된 문자열이므로 하이라이트 결과(HTML)를 그대로 넣어도 된다(내부에서 이스케이프됨).
const html = computed(() => hljs.highlight(props.block.code, { language: props.block.lang }).value)
</script>

<template>
  <figure class="codeblock">
    <figcaption>
      <span class="lang">{{ block.lang }}</span>
      <span class="title">{{ block.title }}</span>
    </figcaption>
    <pre class="code"><code class="hljs" v-html="html" /></pre>
  </figure>
</template>
