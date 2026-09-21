# 浏览器验收截图索引（2026-09-20 增量）

版本：`0.9.0-UAT-RC2`  
环境：Windows 本机，Vue 3 `127.0.0.1:5173`、Spring Boot `127.0.0.1:8080`、FastAPI `127.0.0.1:8001`，H2 文件库  
数据标签：`DEMO_SYNTHETIC`（演示与功能测试数据，不是企业生产数据）

| 编号 | 文件 | 账号/页面 | 核对事实 |
|---|---|---|---|
| UI-01 | `01-login.png` | 公共登录页 | 四个本地演示账号入口、平台名称和人工确认说明 |
| UI-02 | `02-buyer-dashboard.png` | `buyer` / 采购总览 | 真实后端指标、订单事件、风险列表和演示数据声明 |
| UI-03 | `03-forecast-real-run.png` | `buyer` / 14 天预测 | 选择器、标题与批次均为 `MAT-BOX-05`；批次 `FC-20260824205521-0469B`，2026-08-24 至 2026-09-06 共 14 点，XGBoost 指标和 `DEMO_SYNTHETIC` 标签 |
| UI-04 | `04-ai-parse-preview.png` | `buyer` / AI 语义解析 | `MAT-BOX-05`、20 件、2026-09-01；provider=`rule`；Schema/业务校验和影响预览；未执行确认写入 |
| UI-05 | `05-supplier-orders.png` | `supplier` / 采购订单 | 2026-08-25 重新取得并逐图核对；仅显示绑定供应商订单，订单 `PO-202608-001` 为已完成 |
| UI-06 | `06-admin-users.png` | `admin` / 用户与角色 | 四个账号、角色、供应商绑定、版本和管理员专属操作 |
| UI-07 | `07-manager-dashboard.png` | `manager` / 管理总览 | 2026-08-25 重新取得；顶部仅保留刷新动作，不显示采购创建按钮；展示管理范围导航、真实后端汇总与审计入口 |
| UI-08 | `08-scenario-buyer.png` | `buyer` / 采购情景推演 | 2026-09-20 当前工作树；冻结预览 `SCN-20260920220442-5F363`，展示 XGBoost/`DEMO_SYNTHETIC` 来源、0→100 建议量、金额、指纹、逐日投影和人工确认入口 |
| UI-09 | `09-scenario-manager-readonly.png` | `manager` / 采购情景推演 | 同一冻结预览的管理复核视图；显示“不能生成采购需求”，页面无确认按钮 |
| UI-10 | `10-scenario-mobile.png` | `buyer` / 390px 展开结果 | 展开结果后仍保持整页 375px 宽，宽表在卡片内部滚动；按钮、事实摘要和状态可读 |
| UI-11 | `11-ai-local-llm-preview.png` | `buyer` / AI 语义解析 | 真实本地 Qwen2.5-1.5B 预览；显示 provider/model/prompt、字段来源、人工确认和安全边界 |
| UI-12 | `12-noto-sans-sc-materials.png` | `buyer` / 物料 | 2026-09-21 当前工作树；Noto Sans SC Variable 离线字体实际渲染，浏览器计算样式已核对 |
| UI-13 | `13-admin-current.png` | `admin` / 用户与角色 | 2026-09-21 当前工作树；管理员导航、用户维护和角色绑定边界 |
| UI-14 | `14-supplier-current.png` | `supplier` / 采购订单 | 2026-09-21 当前工作树；供应商只能看到绑定范围内订单和协同入口 |
| UI-15 | `15-manager-current.png` | `manager` / 管理总览 | 2026-09-21 当前工作树；管理分析视图和只读业务边界 |
| UI-16 | `16-buyer-local-llm-current.png` | `buyer` / AI 语义解析 | 2026-09-21 当前工作树；本地 Qwen provider、字段证据、人工确认和规则降级说明 |
| UI-17 | `17-buyer-local-llm-mobile.png` | `buyer` / 390px AI 语义解析 | 2026-09-21 当前工作树；窄屏布局、内容可读性和无整页横向溢出 |
| UI-18 | `18-buyer-final-dashboard.png` | `buyer` / 最终总览复核 | 2026-09-21 本轮最终运行页面，后端连接模式与真实数据；控制台 0 errors / 0 warnings |
| UI-19 | `19-analysis-report-desktop.png` | `buyer` / 经营分析报告 1440px | 真实本地 Qwen 辅助排序；统计快照、模型/提示协议、数据指纹、优先行动和只读边界可见 |
| UI-20 | `20-analysis-report-mobile.png` | `buyer` / 经营分析报告 390px | 窄屏下报告摘要、事实网格和本地模型标识可读；页面宽度 375/375，无整页横向溢出 |
| UI-21 | `21-work-queue-desktop.png` | `buyer` / 我的待办 1440px | 跨模块只读待办、优先级、分类筛选和原单据入口可见；真实后端数据 |
| UI-22 | `22-work-queue-mobile.png` | `buyer` / 我的待办 390px | 摘要与待办列表在窄屏可读；页面宽度 375/375，无整页横向溢出 |

UI-01—UI-07 仍只证明 RC2 当时状态；UI-08—UI-10 是 2026-09-20 证据；UI-11—UI-22 是 2026-09-21 本地模型、字体、四角色、经营分析和待办中心当前工作树证据。对应巡检控制台为 `0 errors / 0 warnings`。本索引证明内部候选版巡检，不代表用户个人 UAT 已通过。
