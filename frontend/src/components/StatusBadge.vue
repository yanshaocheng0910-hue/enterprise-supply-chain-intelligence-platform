<template>
  <span class="status-label" :class="toneClass">
    {{ label }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{ label: string; tone?: 'olive' | 'blue' | 'success' | 'warning' | 'danger' | 'neutral' }>(), { tone: 'neutral' })
const toneClass = computed(() => props.tone)
</script>
