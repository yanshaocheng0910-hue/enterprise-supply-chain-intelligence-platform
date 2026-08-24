# SCIC 供应链数智化协同平台前端

Vue 3 + TypeScript + Vite + Element Plus + ECharts + Pinia + Vue Router + Axios。

## 启动

在 `D:\论文\frontend` 执行：

```powershell
$env:npm_config_cache='D:\论文\.cache\npm'
npm install
npm run dev
```

默认将 `/api` 代理到 `http://localhost:8080`。应用请求基地址为 `/api/v1`，可用 `VITE_API_BASE_URL` 覆盖。

## 演示回退

首次纯前端预览默认允许演示回退。后端请求失败后，界面会显示“演示回退模式”，并在页面中标注样例数据；登录页提供 `admin/123456`、`buyer/123456`、`supplier/123456`、`manager/123456`。

联调或验收时建议关闭：

```powershell
$env:VITE_ENABLE_DEMO_FALLBACK='false'
npm run dev
```

关闭后，后端不可用会在对应页面显示明确错误，不会替换为假数据。

## API 路径归一化

视图使用业务语义路径，`src/services/api.ts` 集中映射至后端 `/api/v1` 前缀：

- `/auth`、`/dashboard` 保持原路径
- 主档映射到 `/master-data`
- 导入映射到 `/imports`
- 采购需求、计划、订单映射到 `/procurement`
- 交付、收货、对账映射到 `/collaboration`
- 预测、预警、AI 解析映射到 `/intelligence`
- 审计映射到 `/system/audit-logs`

如后端契约最终字段不同，只需在 `src/services/api.ts` 与各页面请求的数据适配处调整。

## 构建

```powershell
$env:npm_config_cache='D:\论文\.cache\npm'
npm run build
```
