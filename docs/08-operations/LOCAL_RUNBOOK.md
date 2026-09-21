# 本地运行手册（工作版）

文档编号：SCIC-OPS-001  
基线日期：2026-08-24  
当前状态：**UAT 候选，非最终 V1.0**

> 本手册面向 D 盘本地联调。便携 MySQL 已独立验收，Docker 配置仍只做静态检查；本机没有 Docker 时不要把 Compose 文件当作已运行证据。学校正式运维 Word 模板本轮未填写，仅保留项目内工作手册。

## 1. 前置条件

- Windows PowerShell；项目根目录 `D:\论文`。
- Python（建议 3.11，当前 venv 使用可兼容版本）、Java 17、Maven、Node/npm。
- `ai-service\.venv` 已创建并安装 `ai-service\requirements.txt`；可选 XGBoost 不影响 MA7 基础路径。
- 本地大模型首次使用时执行 `scripts\install-local-llm-runtime.ps1`。Qwen GGUF 与 llama.cpp 均安装到项目 D 盘，不要求登录或云端 API key。
- 复制根目录 `.env.example` 为 `.env`，替换 JWT、AI token 和数据库密码等 CHANGE_ME 值。不要把真实密钥提交到仓库。
- 依赖缓存：`D:\论文\.cache\pip`、`D:\论文\.cache\npm`、`D:\论文\.cache\maven`；运行数据和日志：`D:\论文\.data`。

## 2. 一键启动（本地三服务）

从项目根目录执行：

```powershell
Set-Location D:\论文
.\scripts\start-dev.ps1
```

脚本会：创建 D 盘运行目录；读取根 `.env`；当本地 provider 已配置时先启动 llama.cpp 11435，再启动 FastAPI 8001、Spring Boot 8080 和 Vite 5173；每个后台进程使用隐藏窗口；把 stdout/stderr 写入 `.data\logs`；把准确 PID 写入 `.data\run\dev-processes.json`。前端依赖已安装时可跳过 npm 安装：

```powershell
.\scripts\start-dev.ps1 -SkipFrontendInstall
```

如果已有可执行的后端 jar，脚本优先运行 `backend\target\*.jar`；否则调用 Maven 的 `spring-boot:run`。AI 服务始终使用 `ai-service\.venv\Scripts\python.exe`。

## 3. 停止与验证

```powershell
.\scripts\verify.ps1 -StaticOnly
.\scripts\stop-dev.ps1
```

`stop-dev.ps1` 只按当前 manifest 的 PID 树停止并删除 manifest，不删除 H2、MySQL、日志、缓存或 CSV。重新启动前可查看 `.data\logs\*.log`；不要使用未经核对的通配符杀进程。

服务检查地址：

- AI：`GET http://127.0.0.1:8001/health`（无需 token）。
- 后端：`GET http://127.0.0.1:8080/actuator/health`。
- 前端：`http://127.0.0.1:5173`。
- 本地模型：`GET http://127.0.0.1:11435/health`；只允许回环访问。

前端登录后通过 `/api` 访问后端；AI 只由后端以 `X-Service-Token` 调用。

## 4. 开发账号与数据

V2 种子提供 `admin`、`buyer`、`supplier`、`manager` 四个演示账号，默认密码 `123456`。仅限本地演示，部署前必须改密。H2 默认文件在 `.data\h2\scic`；如需重置演示数据，先停止服务并备份/确认该目录，按项目迁移重新初始化，不能把删除操作写进日常停止脚本。

四份 CSV 模板位于 `samples\import`：供应商、物料、库存、需求历史。导入顺序通常为主数据 → 库存 → 需求历史；先 Preview 再 Commit。示例数据带演示属性，不能当作真实 ERP/SRM 生产数据。

## 5. 配置要点

根 `.env.example` 中的常用变量：

- `SCIC_DATA_DIR`、`SCIC_LOG_DIR`：D 盘数据/日志目录；
- `SCIC_SERVER_PORT`、`SCIC_AI_SERVICE_URL`、`SCIC_AI_SERVICE_TOKEN`：后端和 AI 地址/令牌；
- `CORS_ALLOWED_ORIGINS`、`CORS_ALLOW_ORIGINS`：显式前端来源；
- `SPRING_DATASOURCE_URL`：默认 H2；改 MySQL 前先准备数据库和 profile；
- `LLM_PROVIDER=openai-compatible`、`OPENAI_BASE_URL=http://127.0.0.1:11435/v1`、`OPENAI_MODEL=qwen2.5:1.5b`；`LLM_LOCAL_ONLY=true` 时非本机地址会被拒绝，业务文本不会发送到外部；
- `PIP_CACHE_DIR`、`npm_config_cache`、`MAVEN_OPTS`：指向 D 盘缓存。

## 6. 故障排查

| 症状 | 处理 |
|---|---|
| AI 401 | 核对 `.env` 的 token 与后端 `SCIC_AI_SERVICE_TOKEN` 完全一致；不要把 token 写进日志 |
| AI 解析 provider 为 rule | 查看页面中的降级原因与 `.data\logs\local-llm*.log`；本地模型不可用时规则降级是受控行为，不算真实模型成功 |
| 11434 被 Ollama 占用 | 本项目 llama.cpp 固定使用 11435，不需要关闭或依赖 Ollama；不要把端口改回 11434 |
| 后端未启动 | 查看 `.data\logs\backend.stderr.log`，确认 Java/Maven、端口和 H2 路径 |
| 手工执行 Maven 出现 class file 61 / Java 8 错误 | 系统 PATH 的 `java` 可能仍是 Java 8；优先使用 `scripts/start-dev.ps1` 自动选择 Java 17+，或先把 Java 17/21 的 `JAVA_HOME\bin` 放到当前终端 PATH 再执行 Maven |
| 前端页面请求 404 | 查看浏览器网络、后端日志和 `api.ts` 的集中路径映射；候选版已对齐当前接口，若后续变更产生差异则按 UAT 编号记录，不绕过后端权限 |
| npm/pip 下载失败 | 检查 D 盘缓存、网络或准备离线 wheel；不要把缓存改回系统盘 |
| 预测返回 MA7 | 检查样本有效天数/非零天数、XGBoost 可选依赖和 `fallback_reason`；MA7 是受控降级 |
| `HORIZON_INSUFFICIENT` | `lead_time` 大于 14，业务层不得生成自动建议量；调整输入或人工处理 |
| 停止脚本提示 legacy manifest 时间不匹配 | 确认使用最新版 `scripts/local-process-identity.ps1`；旧版本会把 `ConvertFrom-Json` 已解析的 UTC 时间再次本地化。不要绕过身份校验或批量结束 Java 进程 |

## 7. 便携 MySQL 独立验收

便携 MySQL、数据和日志均在 D 盘，和日常 H2 演示环境隔离：

```powershell
Set-Location D:\论文
.\scripts\install-local-mysql.ps1
.\scripts\start-local-mysql.ps1 -Database scic_platform_uat_clean -User scic_uat -Password '<本机强密码>'
# 先使用 Java 17+ 和 D 盘 Maven 缓存构建后端 JAR
.\scripts\start-mysql-uat-backend.ps1 -Database scic_platform_uat_clean -User scic_uat -Password '<同一密码>'
$env:SCIC_UAT_PASSWORD = '本地演示账号密码'
.\ai-service\.venv\Scripts\python.exe .\scripts\verify_mysql_uat.py
```

MySQL 默认监听 `127.0.0.1:3307`，验收后端默认监听 `127.0.0.1:8081`。报告写入 `output\acceptance`，不写数据库密码和 JWT。停止顺序为 `stop-mysql-uat-backend.ps1`、`stop-local-mysql.ps1`；停止脚本保留数据、日志和报告，并核对 PID、启动时间、程序路径、参数及监听端口，不能用通配符杀进程。

2026-09-21 已在 MySQL 8.4.11 干净库完成 V1—V5 和 164/164 验收；这证明独立数据库路径，不等于 Docker Compose 已部署。

## 8. Compose 静态配置说明

`docker-compose.yml` 包含 MySQL、AI、backend、frontend 四个服务，数据挂载到 `D:\论文\.data\mysql`，AI 不映射宿主端口，Nginx 将 `/api/` 转给 backend。由于本机未安装 Docker，本轮只核对 YAML、变量和路径，未执行 `docker compose up`、健康检查或全新环境复现；最终 V1.0 前必须在有 Docker 的干净环境补做并留证。

## 9. 学校材料声明

学校给定 Word/Excel 模板本轮没有填写或伪造内容，只在 `docs/05-experiments/EVIDENCE_INDEX.md` 和交付物登记中注明来源与状态。UAT、测试和实验冻结后，再按学校正式模板汇总。
