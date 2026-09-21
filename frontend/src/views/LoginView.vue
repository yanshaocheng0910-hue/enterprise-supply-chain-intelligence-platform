<template>
  <div class="login-page">
    <section class="login-aside" aria-label="平台介绍">
      <div class="login-brand"><div class="brand-mark">SCIC</div><span>SCIC</span></div>
      <div>
        <h1>把采购判断，<br />放回业务现场。</h1>
        <p>从需求预测到订单履约，再到收货与对账，围绕证据组织每一个协同动作。</p>
      </div>
      <div class="login-proof">
        <span><i></i>14 天需求窗口与库存缺口</span>
        <span><i></i>采购、供应商、管理者分权协同</span>
        <span><i></i>AI 解析先预览，确认后才执行</span>
      </div>
    </section>

    <section class="login-form-wrap">
      <div class="login-form">
        <div class="eyebrow">SUPPLY CHAIN WORKBENCH</div>
        <h2>登录工作台</h2>
        <p class="intro">使用组织账号进入对应业务视图。所有关键操作都会保留审计记录。</p>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
          <el-form-item label="账号" prop="username">
            <el-input v-model="form.username" autocomplete="username" placeholder="请输入账号" @keyup.enter="submit" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" show-password autocomplete="current-password" placeholder="请输入密码" @keyup.enter="submit" />
          </el-form-item>
          <div v-if="loginMessage" class="inline-error" role="alert"><WarningFilled />{{ loginMessage }}</div>
          <el-button type="primary" native-type="button" :loading="auth.loading" @click="submit">进入工作台</el-button>
        </el-form>

        <div class="demo-accounts">
          <h3>演示账号提示</h3>
          <div class="account-grid">
            <button v-for="account in accounts" :key="account.username" class="account-chip" type="button" @click="fill(account)">
              <strong>{{ account.label }}</strong>
              <span>{{ account.username }} / 123456</span>
            </button>
          </div>
          <p class="app-footer-note">首次纯前端预览可使用演示账号。连接后端后，认证结果以服务端为准。</p>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const loginMessage = computed(() => auth.errorMessage || (route.query.reason === 'expired' ? '登录已失效，请重新登录后继续。' : ''))
const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }, { min: 6, message: '密码至少 6 位', trigger: 'blur' }],
}
const accounts = [
  { username: 'admin', label: '系统管理员' },
  { username: 'buyer', label: '采购协同人员' },
  { username: 'supplier', label: '供应商' },
  { username: 'manager', label: '企业管理人员' },
]

function fill(account: { username: string }) {
  form.username = account.username
  form.password = '123456'
  auth.errorMessage = ''
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  try {
    await auth.login(form.username.trim(), form.password)
    const requestedRedirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    const redirect = requestedRedirect.startsWith('/') && !requestedRedirect.startsWith('//') ? requestedRedirect : auth.role === 'ADMIN' ? '/admin-users' : '/dashboard'
    await router.replace(redirect)
  } catch {
    // The store keeps the backend error or demo credential error visible in the form.
  }
}
</script>

<style scoped>
.login-form .eyebrow { margin-bottom: 14px; }
.login-form .inline-error { margin: -3px 0 17px; }
.login-form .inline-error svg { flex: 0 0 auto; width: 15px; margin-top: 2px; }
</style>
