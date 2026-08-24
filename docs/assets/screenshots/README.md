# 浏览器验收截图索引（2026-08-25 复核）

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
| UI-07 | `07-manager-dashboard.png` | `manager` / 管理总览 | 管理者只读/审批相关导航、真实后端汇总与审计入口 |

浏览器控制台最终以全新登录页会话复核：`0 errors / 0 warnings`。本索引证明候选版页面巡检，不代表用户个人 UAT 已通过。
