# 测试执行记录（2026-09-21）

文档编号：SCIC-TEST-EXEC-20260921
执行日期：2026-09-21（Asia/Shanghai）
测试对象：`0.9.0-UAT-RC2` 基线上的未发布候选工作树
结论：**项目内自动化、构建、静态/运行检查、本地 AI 链路和便携 MySQL 8.4.11 独立验收通过；用户 UAT 与 Docker Compose 容器验收未完成。**

## 1. 最终复跑结果

| 检查 | 结果 | 证据/说明 |
|---|---|---|
| Spring Boot 集成测试 | **57 passed** | `PlatformIntegrationTest` 22 项、`AiFailureIntegrationTest` 35 项；0 failures / 0 errors / 0 skipped；Java 21、H2 MySQL 模式 |
| FastAPI 测试 | **23 passed** | `ai-service/tests/`；覆盖 Schema、provider、原文证据校正、本地模型降级、预测和经营分析闭集契约 |
| 前端类型检查与生产构建 | **通过** | `npm.cmd run build`；Vite 共转换 2303 个模块；保留大 chunk 非阻断警告 |
| 静态交付检查 | **通过** | `scripts/verify.ps1 -StaticOnly` |
| 本地模型评测 | **12/12 passed** | 三类各 4/5/3 条，平均 3266.396 ms；详见 `../05-experiments/AI_PARSE_LOCAL_EVAL_2026-09-21.md` |
| 本地模型不可用降级 | **通过** | 停止模型后返回 `provider=rule`、`providerFallback=true` 和失败原因；恢复后重新健康 |
| 浏览器本地模型预览 | **通过** | BUYER 页面显示本地 provider、模型、Prompt、字段来源并可人工确认；UI-11 |
| 离线字体 | **通过** | Noto Sans SC Variable 随前端打包；浏览器计算样式首选字体正确；UI-12 |
| 四角色当前工作树浏览器巡检 | **通过** | UI-13—UI-17；ADMIN、SUPPLIER、MANAGER、BUYER 及 390px 页面，内部验收不替代用户 UAT |
| 最终 BUYER 工作台复核 | **通过** | 真实后端模式；控制台 0 errors / 0 warnings；UI-18：`docs/assets/screenshots/18-buyer-final-dashboard.png` |
| 便携 MySQL 8.4.11 | **164/164 passed** | 干净库 V1—V5 迁移、四角色 RBAC、完整采购闭环、幂等/隔离/审计、原始数据不变及停用账号令牌撤销；`output/acceptance/mysql-uat-202609210244488FAB74.json` |

> MySQL 的 164/164 是新增 V6 之前的独立证据，不能证明 V6 报告表已在 MySQL 复验；本轮 V6 仅在 H2 运行库和 H2 自动化中通过。

## 1.1 经营分析报告增量执行

| 检查 | 结果 | 事实证据 |
|---|---|---|
| 规则报告与安全边界 | 通过 | FastAPI 单测验证 7 章节、只读提示、远程 LLM 地址拒绝和规则降级 |
| 本地 Qwen 闭集排序 | 通过 | 真实 `qwen2.5:1.5b` 返回闭集排序；最终报告 `provider=openai-compatible`、prompt=`analysis-v2-local-rank-rule-facts` |
| 快照、幂等、权限与审计 | 通过 | Spring 集成测试 #20；同键一份、64 位指纹、生成审计、SUPPLIER 403 |
| 真实后端 API | 通过 | 生成 7 章节、3 条行动、64 位指纹；模型错误输出曾被安全拒绝并规则降级，收窄职责后真实本地模型路径成功 |
| BUYER 页面按钮 | 通过 | POST 200，历史从 3 份增至 4 份，显示“本地模型辅助排序”；控制台 0 errors / 0 warnings |
| 桌面与 390px | 通过 | 1440px、390px 均无整页横向溢出；计算字体 Noto Sans SC Variable；UI-19/UI-20 |
| Docker Compose | **未执行** | 本机没有 Docker；Compose 网络、变量、Dockerfile 和 Nginx 只完成静态检查，不据此宣称容器部署通过 |
| 用户个人 UAT | **待用户执行** | 后续反馈登记到 `docs/00-governance/UAT_FEEDBACK.md` |

## 1.2 角色待办中心增量执行

| 检查 | 结果 | 事实证据 |
|---|---|---|
| 角色与数据范围 | 通过 | 集成测试 #21 验证 BUYER 待办、SUPPLIER 绑定范围隔离、ADMIN 403 |
| 只读边界 | 通过 | 调用前后操作日志数量不变；待办仅按既有业务状态查询和归类 |
| 前端生产构建 | 通过 | `npm.cmd run build`，Vite 转换 2309 个模块 |
| 桌面与 390px | 通过 | 页面宽度分别与视口一致；Noto Sans SC Variable 生效；UI-21/UI-22 |
| 浏览器控制台 | 通过 | 新会话 0 errors / 0 warnings |

## 1.3 订单履约证据链增量执行

| 检查 | 结果 | 事实证据 |
|---|---|---|
| 证据聚合 | 通过 | 真实完成订单 `PO-202608-001` 返回 17 条事件，包含业务记录、审计日志与库存流水 |
| 范围与只读 | 通过 | 集成测试 #22 验证绑定供应商可读、其他供应商订单 404、ADMIN 403、读取前后日志数量不变 |
| 状态事实 | 通过 | 创建节点固定为 `PENDING_CONFIRMATION`；后续状态由历史审计迁移展示，当前阶段由订单真实状态计算 |
| 前端生产构建 | 通过 | `npm.cmd run build`，Vite 转换 2309 个模块 |
| 桌面与 390px | 通过 | 1440px 抽屉可读；390px 页面与抽屉均 390/390，无横向溢出；UI-23/UI-24 |
| 浏览器控制台 | 通过 | 服务重启后的全新会话 0 errors / 0 warnings |

## 2. 本地大模型运行事实

- 模型：Qwen2.5-1.5B-Instruct，Q4_K_M GGUF，模型别名 `qwen2.5:1.5b`。
- 运行时：llama.cpp `b11026`，仅监听 `127.0.0.1:11435`，由 FastAPI 内部调用。
- 模型文件：`.data/models/qwen2.5-1.5b-instruct-q4_k_m.gguf`，1,117,320,736 bytes。
- SHA-256：`6A1A2EB6D15622BF3C96857206351BA97E1AF16C30D7A74EE38970E434E9407E`。
- Prompt：`parse-v3-intent-schema-guarded`。
- 默认安全边界：`LLM_LOCAL_ONLY=true`；配置成非 localhost 主机时拒绝发送业务文本并转入受控规则降级。
- 输出只形成待确认预览；Schema、原文证据、主数据、角色权限和业务状态仍由程序校验，大模型不能直接下单、入库或对账。

## 3. 复跑中的环境问题及处理

第一次并行复跑时，前端已完成构建但 Windows Node 退出阶段出现 libuv 断言；改为 `npm.cmd run build` 串行执行后退出码为 0。后端第一次误用不存在的 `mvnw.cmd`，第二次被系统 Java 8 影响；按项目启动脚本选择 Java 21 并使用 D 盘 Maven 缓存后，54 项测试全部通过。重新打包前旧 Java 进程锁定 JAR；按项目清单安全停止后打包成功。进程校验还发现 JSON UTC 时间被重复应用本地时区，修复后使用 PID、启动时间、可执行文件、JAR 参数和监听端口联合校验。这些执行环境问题均如实保留，不作为功能失败隐藏。

## 4. 真实 MySQL 独立验收

- 运行时：项目 D 盘便携 MySQL `8.4.11`，仅监听 `127.0.0.1:3307`；验收后端监听 `127.0.0.1:8081`，与 H2 演示后端隔离。
- 数据库：`scic_platform_uat_clean`，关闭合成需求历史初始化；由 Flyway 从空库执行 V1—V5。
- 验收脚本：`scripts/verify_mysql_uat.py`；本轮运行号与逐项结果写入上述 JSON，不保存数据库密码或 JWT。
- 结果：164 项全部通过。业务主线覆盖需求、计划提交/审批、订单、供应商确认、通知、发运、到达、分批合格/拒收、入库、对账与完成；同时覆盖错误角色、非法状态、旧版本、重复键、供应商范围及数据保全。
- 安全补强：JWT 每次请求重新读取当前账号状态与角色。临时账号被停用后，停用前签发的 token 下一次访问返回 401；H2 集成测试与真实 MySQL 验收均已覆盖。

## 5. 仍未关闭的发布边界

1. 用户尚未进行个人 UAT，因此当前不能标记最终 V1.0。
2. 真实 MySQL 8 已独立复现；当前机器仍缺少 Docker，Compose 容器启动、容器间网络、Nginx 代理和容器健康检查尚未实测。
3. 本地评测集只有 12 条，是链路与回归样本，不代表生产准确率；论文实验应继续扩充歧义、缺字段、越权和提示注入样本。
4. 示例数据标记为 `DEMO_SYNTHETIC`，不得描述为企业真实生产数据。

## 6. 首批 UAT 修复增量回归

| 检查 | 结果 | 说明 |
|---|---|---|
| 无效会话恢复 | 通过 | BUYER 登录后将访问 token 置为无效并刷新仓库页，自动跳转 `/login?reason=expired&redirect=%2Fwarehouses`，token/session 已清除，登录提示可见 |
| 错误态视觉 | 通过 | 通用警告图标固定 16px；仓库错误、加载、空态互斥；未再出现巨型感叹号覆盖页面 |
| 系统文件选择 | 通过 | 点击“选择 CSV 文件”触发浏览器 file chooser；选择 `samples/import/inventory.csv` 后显示文件名、155 B 和可用提交按钮 |
| multipart 预览 | 通过 | 修复全局 JSON 头后，`POST /api/v1/imports/preview` 成功；3 行库存数据返回 2 条真实 `REFERENCE_NOT_FOUND`，无控制台错误 |
| 模板下载 | 通过 | 页面真实下载 `inventory.csv`，表头和 3 行样例与导入契约一致；另外 3 类模板随包提供 |
| 错误报告下载 | 通过 | 真实下载 `import-errors-IMP-*.csv`，字段为 row/field/code/message/raw，中文内容和服务端错误一致 |
| 响应式与字体 | 通过 | 390px 下 `documentElement.scrollWidth=innerWidth=390`；body 计算字体首选 Noto Sans SC Variable |
| 自动化回归 | 通过 | Spring Boot 54/54；前端 `vue-tsc --noEmit` 与 Vite 生产构建通过 |

增量回归关闭的是 `UAT-090-001/002` 的内部修复验证，最终状态仍为“待用户复验”，不等于完整用户 UAT 通过。
