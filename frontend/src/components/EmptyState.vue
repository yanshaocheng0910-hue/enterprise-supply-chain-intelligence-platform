<template>
  <div class="empty-state">
    <strong>{{ title }}</strong>
    <p>{{ description }}</p>
    <el-button v-if="actionText" type="primary" plain @click="$emit('action')">{{ actionText }}</el-button>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ title: string; description: string; actionText?: string }>(), { actionText: '' })
defineEmits<{ action: [] }>()
</script>
