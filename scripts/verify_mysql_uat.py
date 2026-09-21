#!/usr/bin/env python3
"""Real MySQL acceptance, using only the public API and isolated UAT records.

Run against the separately started MySQL backend (never starts/stops services):
    python scripts/verify_mysql_uat.py --base-url http://127.0.0.1:8081

Demo usernames are configurable with --admin-user / --buyer-user /
--supplier-user / --manager-user. Passwords are read from SCIC_UAT_PASSWORD,
or role-specific SCIC_UAT_ADMIN_PASSWORD / BUYER_PASSWORD / SUPPLIER_PASSWORD /
MANAGER_PASSWORD variables. If omitted, an interactive hidden prompt is used.
Passwords and access tokens are never written to console or the JSON report.
No third-party Python packages are required (Python 3.10 or newer).

An authenticated health response must identify MySQL before business writes.
Every run creates a unique material, supplier, warehouse and temporary supplier
user. The temporary user is disabled at the end; the UAT business records stay
for audit/review. Existing orders and inventory are compared before/after.
Exit 0 means all checks passed; exit 1 means a check failed. This is automated
API acceptance, not a claim that a human has completed personal UAT.
"""

from __future__ import annotations

import argparse
import datetime as dt
import getpass
import hashlib
import json
import os
from pathlib import Path
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


class AcceptanceFailure(Exception):
    """Contains only a safe check identifier, never HTTP bodies or credentials."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Acceptance:
    def __init__(self, args):
        self.args = args
        self.base_url = args.base_url.rstrip("/")
        self.run_id = dt.datetime.now().strftime("%Y%m%d%H%M%S") + secrets.token_hex(3).upper()
        self.tokens = {}
        self.users = {}
        self.created = {}
        self.checks = []
        self.started_at = dt.datetime.now(dt.timezone.utc).isoformat()
        self.temp_user = None
        self.original_orders = None
        self.original_inventory = None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def check(self, identifier, title, condition, **evidence):
        self.checks.append({"id": identifier, "title": title,
                            "status": "PASS" if condition else "FAIL", **evidence})
        print(f"{'PASS' if condition else 'FAIL'} {identifier}: {title}")
        if not condition:
            raise AcceptanceFailure(identifier)

    def api(self, identifier, title, role, method, path, body=None,
            expected=200, error_code=None, key=None, raw=False):
        headers = {"Accept": "application/json", "X-Request-Id": f"uat-{self.run_id}-{identifier}"}
        if role:
            headers["Authorization"] = "Bearer " + self.tokens[role]
        if key is not None:
            headers["Idempotency-Key"] = f"uat-{self.run_id}-{key}"
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        if payload is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
        request = urllib.request.Request(self.base_url + path, data=payload, headers=headers, method=method)
        start = time.monotonic()
        try:
            try:
                with self.opener.open(request, timeout=self.args.timeout) as response:
                    status, response_body = response.status, response.read()
            except urllib.error.HTTPError as exc:
                status, response_body = exc.code, exc.read()
            parsed = json.loads(response_body)
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as exc:
            self.check(identifier, title, False, method=method, path=path,
                       failure_type=type(exc).__name__)
        actual_error = (parsed.get("error") or {}).get("code")
        valid = status == expected and (error_code is None or actual_error == error_code)
        if not raw:
            valid = valid and parsed.get("success") is (expected < 400)
        self.check(identifier, title, valid, method=method, path=path, role=role,
                   expected_http=expected, actual_http=status,
                   error_code=actual_error, request_id=parsed.get("requestId"),
                   elapsed_ms=round((time.monotonic() - start) * 1000, 2))
        return parsed if raw else parsed.get("data")

    def login(self, role, username, password, expected_role=None):
        result = self.api(f"AUTH-{role}", f"{expected_role or role.upper()} 登录", None,
                          "POST", "/api/v1/auth/login", {"username": username, "password": password})
        self.tokens[role] = result["accessToken"]
        self.users[role] = result["user"]
        self.check(f"AUTH-{role}-ROLE", "登录身份与预期角色一致",
                   result["user"]["role"] == (expected_role or role.upper()))
        me = self.api(f"AUTH-{role}-ME", "当前身份回读", role, "GET", "/api/v1/auth/me")
        self.check(f"AUTH-{role}-ME-ID", "登录与身份回读一致", me["id"] == result["user"]["id"])

    def get(self, identifier, title, path, role="buyer"):
        return self.api(identifier, title, role, "GET", path)

    def post(self, identifier, title, path, body, role="buyer", **kwargs):
        return self.api(identifier, title, role, "POST", path, body, **kwargs)

    def action(self, identifier, title, path, row, action, role="buyer", **kwargs):
        return self.post(identifier, title, f"{path}/{row['id']}/actions",
                         {"action": action, "expectedVersion": row["version"], "note": "UAT " + self.run_id},
                         role, **kwargs)

    @staticmethod
    def digest(rows):
        return hashlib.sha256(json.dumps(rows, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()

    def inventory(self, identifier, expected):
        rows = self.get(identifier, "读取专用物料库存", "/api/v1/master-data/inventory")
        matches = [row for row in rows if row["material_code"] == self.created["material_code"]]
        correct = len(matches) == (0 if expected == 0 else 1)
        if expected:
            correct = correct and matches[0]["warehouse_code"] == self.created["warehouse_code"]
            correct = correct and matches[0]["on_hand_qty"] == expected
            correct = correct and matches[0]["available_qty"] == expected and matches[0]["reserved_qty"] == 0
        self.check(identifier + "-QTY", "库存仅累计合格数量", correct,
                   expected_on_hand=expected,
                   actual_on_hand=sum(row["on_hand_qty"] for row in matches))

    def run(self, passwords):
        for role in ("admin", "buyer", "supplier", "manager"):
            self.login(role, getattr(self.args, role + "_user"), passwords[role])
        health = self.api("ENV-01", "读取已认证数据库健康信息", "admin", "GET", "/actuator/health", raw=True)
        db = health.get("components", {}).get("db", {})
        product = db.get("details", {}).get("database")
        self.check("ENV-02", "业务写入前确认真实 MySQL 数据源",
                   health.get("status") == "UP" and db.get("status") == "UP" and product == "MySQL",
                   database_product=product, database_status=db.get("status"))
        self.api("AUTH-ANON", "未登录不能读取业务数据", None, "GET", "/api/v1/dashboard/summary",
                 expected=401, error_code="UNAUTHORIZED")
        self.api("AUTH-BAD", "无效账号密码被拒绝", None, "POST", "/api/v1/auth/login",
                 {"username": "missing_" + self.run_id, "password": secrets.token_urlsafe(24)},
                 expected=401, error_code="INVALID_CREDENTIALS")

        for role in ("admin", "buyer", "supplier", "manager"):
            self.get("QUERY-DASH-" + role, "角色看板读取", "/api/v1/dashboard/summary", role)
        for role, paths in {
            "admin": ["/admin/users", "/admin/roles", "/system/audit-logs", "/system/data-provenance"],
            "buyer": ["/master-data/suppliers", "/master-data/materials", "/master-data/warehouses",
                      "/master-data/inventory", "/procurement/demands", "/procurement/plans",
                      "/procurement/orders", "/collaboration/delivery-notices", "/collaboration/receipts",
                      "/collaboration/reconciliations", "/system/warnings"],
            "manager": ["/procurement/plans", "/procurement/orders", "/master-data/inventory", "/system/audit-logs"],
            "supplier": ["/procurement/orders", "/collaboration/delivery-notices", "/collaboration/receipts",
                         "/collaboration/reconciliations"],
        }.items():
            for index, path in enumerate(paths, 1):
                self.get(f"QUERY-{role}-{index:02}", "关键业务查询", "/api/v1" + path, role)
        for index, (role, path) in enumerate([
            ("supplier", "/master-data/inventory"), ("supplier", "/master-data/materials"),
            ("supplier", "/procurement/plans"), ("supplier", "/procurement/demands"),
            ("supplier", "/system/warnings"), ("supplier", "/system/audit-logs"),
            ("buyer", "/admin/users"), ("manager", "/admin/users"), ("admin", "/procurement/orders"),
        ], 1):
            self.api(f"RBAC-READ-{index:02}", "越权查询被拒绝", role, "GET", "/api/v1" + path,
                     expected=403, error_code="FORBIDDEN")

        self.original_orders = self.get("BASELINE-ORDER", "保存原有订单校验摘要", "/api/v1/procurement/orders")
        self.original_inventory = self.get("BASELINE-STOCK", "保存原有库存校验摘要", "/api/v1/master-data/inventory")
        suffix = self.run_id
        self.created.update(material_code="UM-" + suffix, warehouse_code="UW-" + suffix,
                            supplier_code="US-" + suffix, supplier_username="uat_" + suffix.lower())
        supplier = self.post("FIXTURE-SUPPLIER", "创建独立验收供应商", "/api/v1/master-data/suppliers",
                             {"supplierCode": self.created["supplier_code"], "supplierName": "MySQL验收供应商 " + suffix,
                              "levelCode": "B"})
        self.created["supplier_id"] = supplier["id"]
        material = self.post("FIXTURE-MATERIAL", "创建独立验收物料", "/api/v1/master-data/materials",
                             {"materialCode": self.created["material_code"], "materialName": "MySQL验收物料 " + suffix,
                              "category": "UAT", "unit": "件", "safetyStock": 0, "minOrderQty": 1,
                              "packSize": 1, "leadTimeDays": 1, "standardPrice": 12.5})
        self.created["material_id"] = material["id"]
        warehouse = self.post("FIXTURE-WAREHOUSE", "创建独立验收仓库", "/api/v1/master-data/warehouses",
                              {"warehouseCode": self.created["warehouse_code"], "warehouseName": "MySQL验收仓库 " + suffix,
                               "locationText": "专用自动验收数据"})
        self.created["warehouse_id"] = warehouse["id"]
        isolated_password = secrets.token_urlsafe(24)
        self.temp_user = self.post("FIXTURE-USER", "创建验收专用供应商账号", "/api/v1/admin/users",
                                   {"username": self.created["supplier_username"], "displayName": "MySQL验收供应商账号",
                                    "password": isolated_password, "roleCode": "SUPPLIER", "supplierId": supplier["id"]}, "admin")
        self.created["supplier_user_id"] = self.temp_user["id"]
        self.login("isolated", self.created["supplier_username"], isolated_password, "SUPPLIER")
        isolated_password = None
        self.inventory("STOCK-EMPTY", 0)

        demands = "/api/v1/procurement/demands"
        plans = "/api/v1/procurement/plans"
        orders = "/api/v1/procurement/orders"
        notices = "/api/v1/collaboration/delivery-notices"
        receipts = "/api/v1/collaboration/receipts"
        reconciliations = "/api/v1/collaboration/reconciliations"
        expected_date = (dt.date.today() + dt.timedelta(days=7)).isoformat()
        demand_body = {"materialCode": self.created["material_code"], "quantity": 10,
                       "expectedDate": expected_date, "priority": "NORMAL", "notes": "UAT " + suffix}
        for role in ("admin", "supplier", "manager"):
            self.post("RBAC-DEMAND-" + role, "非采购角色不能新建需求", demands, demand_body, role,
                      expected=403, error_code="FORBIDDEN")
        demand = self.post("FLOW-01", "采购员创建需求草稿", demands, demand_body)
        self.created["demand_id"] = demand["id"]
        self.check("FLOW-01-STATE", "需求草稿数量与来源正确",
                   demand["status"] == "DRAFT" and demand["quantity"] == 10 and demand["source_type"] == "MANUAL")
        plan_body = {"planName": "MySQL UAT " + suffix, "items": [
            {"demandId": demand["id"], "materialCode": self.created["material_code"],
             "supplierCode": self.created["supplier_code"], "quantity": 10, "unitPrice": 12.5, "expectedDate": expected_date}]}
        plan = self.post("FLOW-02", "采购需求进入计划", plans, plan_body)
        self.created["plan_id"] = plan["id"]
        self.check("FLOW-02-STATE", "计划草稿金额和需求关联正确", plan["status"] == "DRAFT" and
                   plan["total_amount"] == 125 and plan["items"][0]["demand_id"] == demand["id"])
        self.post("BOUND-PLAN-REUSE", "已入计划需求不能重复使用", plans, plan_body,
                  expected=409, error_code="INVALID_DEMAND")
        self.action("BOUND-PLAN-APPROVE", "采购员不能自批计划", plans, plan, "APPROVE",
                    expected=403, error_code="FORBIDDEN")
        self.post("BOUND-ORDER-EARLY", "未审批计划不能生成订单", f"{plans}/{plan['id']}/generate-orders",
                  {"expectedVersion": plan["version"]}, key="order-early", expected=409, error_code="ILLEGAL_STATE_TRANSITION")
        plan = self.action("FLOW-03", "采购员提交计划审批", plans, plan, "SUBMIT")
        self.check("FLOW-03-STATE", "计划进入待审批状态", plan["status"] == "PENDING_APPROVAL")
        self.action("BOUND-PLAN-VERSION", "过期计划版本被拒绝", plans, {**plan, "version": 0}, "APPROVE", "manager",
                    expected=409, error_code="VERSION_CONFLICT")
        plan = self.action("FLOW-04", "经理审批采购计划", plans, plan, "APPROVE", "manager")
        self.check("FLOW-04-STATE", "计划审批通过", plan["status"] == "APPROVED")
        generated_body = {"expectedVersion": plan["version"]}
        generated = self.post("FLOW-05", "已审批计划生成唯一订单", f"{plans}/{plan['id']}/generate-orders",
                              generated_body, key="order")
        self.check("FLOW-05-COUNT", "计划只生成一个待确认订单", len(generated) == 1 and generated[0]["status"] == "PENDING_CONFIRMATION")
        order = generated[0]
        self.created["order_id"] = order["id"]
        self.created["order_no"] = order["order_no"]
        order_path = f"{orders}/{order['id']}"
        replay = self.post("IDEMP-ORDER", "生成订单原请求重放", f"{plans}/{plan['id']}/generate-orders",
                           generated_body, key="order")
        self.check("IDEMP-ORDER-ID", "重复请求返回同一订单", len(replay) == 1 and replay[0]["id"] == order["id"])
        self.post("IDEMP-ORDER-OTHER", "换幂等键仍不能重复生成订单", f"{plans}/{plan['id']}/generate-orders",
                  generated_body, key="order-other", expected=409, error_code="ORDER_ALREADY_GENERATED")
        self.api("SCOPE-ORDER", "原演示供应商不能读取验收供应商订单", "supplier", "GET", order_path,
                 expected=404, error_code="RESOURCE_NOT_FOUND")
        self.action("SCOPE-ORDER-WRITE", "其他供应商不能确认订单", orders, order, "CONFIRM", "supplier",
                    expected=403, error_code="SUPPLIER_SCOPE_VIOLATION")
        self.action("BOUND-ORDER-BUYER", "采购员不能代供应商确认", orders, order, "CONFIRM",
                    expected=403, error_code="FORBIDDEN")
        self.action("BOUND-ORDER-JUMP", "订单禁止绕过到货通知直接发运", orders, order, "SHIP", "isolated",
                    expected=422, error_code="USE_DELIVERY_NOTICE_WORKFLOW")
        item_id = order["items"][0]["id"]

        def receipt_body(version, received=4, qualified=3, rejected=1, notice_id=None):
            result = {"orderId": order["id"], "orderVersion": version,
                      "warehouseCode": self.created["warehouse_code"], "notes": "UAT " + suffix,
                      "items": [{"orderItemId": item_id, "receivedQty": received, "qualifiedQty": qualified,
                                 "rejectedQty": rejected, "varianceReason": "验收分批到货和质量拒收"}]}
            if notice_id is not None:
                result["deliveryNoticeId"] = notice_id
            return result

        self.post("BOUND-RECEIPT-EARLY", "到达前不能收货入库", receipts, receipt_body(order["version"]),
                  key="receipt-early", expected=409, error_code="ILLEGAL_STATE_TRANSITION")
        self.inventory("STOCK-EARLY-BLOCKED", 0)
        order = self.action("FLOW-06", "供应商确认订单", orders, order, "CONFIRM", "isolated")
        self.action("BOUND-ORDER-CONFIRM-TWICE", "已确认订单不能再次确认", orders, order, "CONFIRM", "isolated",
                    expected=409, error_code="ILLEGAL_STATE_TRANSITION")
        order = self.action("FLOW-07", "供应商备货", orders, order, "PREPARE_SHIPMENT", "isolated")
        notice_body = {"orderId": order["id"], "expectedArrivalAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                       "carrierName": "UAT", "trackingNo": "UAT-" + suffix, "items": [{"orderItemId": item_id, "quantity": 10}]}
        notice = self.post("FLOW-08", "供应商建立到货通知", notices, notice_body, "isolated")
        self.created["notice_id"] = notice["id"]
        self.post("BOUND-NOTICE-DUP", "有效到货通知不能重复新建", notices, notice_body, "isolated",
                  expected=409, error_code="DUPLICATE_RESOURCE")
        self.action("BOUND-NOTICE-JUMP", "草稿通知不能直接确认到达", notices, notice, "ARRIVE",
                    expected=409, error_code="ILLEGAL_STATE_TRANSITION")
        notice = self.action("FLOW-09", "供应商提交到货通知", notices, notice, "SUBMIT", "isolated")
        notice = self.action("FLOW-10", "供应商登记发运", notices, notice, "DISPATCH", "isolated")
        self.action("BOUND-NOTICE-ROLE", "供应商不能代采购方确认到达", notices, notice, "ARRIVE", "isolated",
                    expected=403, error_code="FORBIDDEN")
        notice = self.action("FLOW-11", "采购方确认货物到达", notices, notice, "ARRIVE")
        order = self.get("FLOW-11-ORDER", "回读订单到达状态", order_path)
        self.check("FLOW-11-STATE", "通知和订单均已到达", notice["status"] == "ARRIVED" and order["status"] == "ARRIVED")
        first_body = receipt_body(order["version"], notice_id=notice["id"])
        self.post("BOUND-RECEIPT-ROLE", "供应商不能办理入库", receipts, first_body, "isolated",
                  key="receipt-role", expected=403, error_code="FORBIDDEN")
        self.post("BOUND-RECEIPT-SUM", "合格与拒收数量之和必须等于实收", receipts,
                  receipt_body(order["version"], 4, 4, 1, notice["id"]), key="receipt-sum",
                  expected=422, error_code="BUSINESS_RULE_VIOLATION")
        first = self.post("FLOW-12", "首批实收4件：合格3件、拒收1件", receipts, first_body, key="receipt-1")
        self.created["receipt_ids"] = [first["id"]]
        self.check("FLOW-12-QTY", "收货明细保留真实合格与拒收数量", first["items"][0]["received_qty"] == 4 and
                   first["items"][0]["qualified_qty"] == 3 and first["items"][0]["rejected_qty"] == 1)
        self.inventory("STOCK-FIRST", 3)
        replay = self.post("IDEMP-RECEIPT", "首批收货原请求重放", receipts, first_body, key="receipt-1")
        self.check("IDEMP-RECEIPT-ID", "重复收货返回同一收货单", replay["id"] == first["id"])
        self.inventory("STOCK-REPLAY", 3)
        order = self.get("FLOW-12-ORDER", "回读部分收货订单", order_path)
        self.check("FLOW-12-STATE", "拒收不进入合格实收且订单保持部分收货",
                   order["status"] == "PARTIALLY_RECEIVED" and order["items"][0]["received_qty"] == 3)
        self.post("BOUND-RECON-EARLY", "未全部合格收齐不能对账", reconciliations,
                  {"orderId": order["id"], "orderVersion": order["version"]}, key="recon-early",
                  expected=409, error_code="ILLEGAL_STATE_TRANSITION")
        self.post("BOUND-RECEIPT-VERSION", "不同请求使用过期订单版本被拒绝", receipts, first_body, key="receipt-stale",
                  expected=409, error_code="VERSION_CONFLICT")
        self.post("BOUND-RECEIPT-OVER", "收货不能超过订单或通知剩余数量", receipts,
                  receipt_body(order["version"], 8, 8, 0, notice["id"]), key="receipt-over",
                  expected=422, error_code="BUSINESS_RULE_VIOLATION")
        self.inventory("STOCK-BOUNDARIES", 3)
        second = self.post("FLOW-13", "补收剩余7件合格品", receipts,
                           receipt_body(order["version"], 7, 7, 0, notice["id"]), key="receipt-2")
        self.created["receipt_ids"].append(second["id"])
        self.inventory("STOCK-FINAL", 10)
        order = self.get("FLOW-13-ORDER", "回读全部合格收齐订单", order_path)
        notice = self.get("FLOW-13-NOTICE", "回读全部合格收齐到货通知", f"{notices}/{notice['id']}")
        self.check("FLOW-13-STATE", "订单和通知均收货完成",
                   order["status"] == "RECEIVED" and notice["status"] == "RECEIVED" and order["items"][0]["received_qty"] == 10)
        self.post("BOUND-RECEIPT-CLOSED", "已收齐订单不能用新请求重复收货", receipts,
                  receipt_body(order["version"], 1, 1, 0, notice["id"]), key="receipt-closed",
                  expected=409, error_code="ILLEGAL_STATE_TRANSITION")
        recon_body = {"orderId": order["id"], "orderVersion": order["version"]}
        recon = self.post("FLOW-14", "按合格实收创建对账单", reconciliations, recon_body, key="recon")
        self.created["reconciliation_id"] = recon["id"]
        self.check("FLOW-14-AMOUNT", "订单与合格收货金额一致且差额为零",
                   recon["order_amount"] == 125 and recon["received_amount"] == 125 and recon["difference_amount"] == 0)
        replay = self.post("IDEMP-RECON", "对账创建原请求重放", reconciliations, recon_body, key="recon")
        self.check("IDEMP-RECON-ID", "重复请求返回同一对账单", replay["id"] == recon["id"])
        self.action("BOUND-RECON-COMPLETE", "未确认对账单不能直接完成", reconciliations, recon, "COMPLETE",
                    expected=409, error_code="ILLEGAL_STATE_TRANSITION")
        recon = self.action("FLOW-15", "供应商确认对账金额", reconciliations, recon, "CONFIRM", "isolated")
        recon = self.action("FLOW-16", "采购方完成对账", reconciliations, recon, "COMPLETE")
        order = self.get("FLOW-16-ORDER", "回读订单最终状态", order_path)
        plan = self.get("FLOW-16-PLAN", "回读计划最终状态", f"{plans}/{plan['id']}")
        self.check("FLOW-FINAL", "采购到对账闭环完成",
                   recon["status"] == "COMPLETED" and order["status"] == "COMPLETED" and plan["status"] == "CLOSED",
                   order_status=order["status"], plan_status=plan["status"], reconciliation_status=recon["status"])
        self.action("BOUND-RECON-COMPLETE-TWICE", "完成的对账单不能再次完成", reconciliations, recon, "COMPLETE",
                    expected=409, error_code="ILLEGAL_STATE_TRANSITION")
        all_receipts = self.get("VERIFY-RECEIPTS", "读取独立订单的收货单数量", receipts)
        self.check("VERIFY-RECEIPTS-COUNT", "仅两张有效收货单且失败请求未落账",
                   len([row for row in all_receipts if row["order_id"] == order["id"]]) == 2)
        all_demands = self.get("VERIFY-DEMAND", "需求状态回读", demands)
        own_demand = [row for row in all_demands if row["id"] == demand["id"]]
        self.check("VERIFY-DEMAND-STATE", "原采购需求标记为已入计划", len(own_demand) == 1 and own_demand[0]["status"] == "PLANNED")
        for entity, path in [("NOTICE", f"{notices}/{notice['id']}"), ("RECEIPT", f"{receipts}/{first['id']}"),
                             ("RECON", f"{reconciliations}/{recon['id']}")]:
            self.api("SCOPE-" + entity, "原供应商不能发现验收供应商单据", "supplier", "GET", path,
                     expected=404, error_code="RESOURCE_NOT_FOUND")
        visible_orders = self.get("SCOPE-LIST", "专用供应商订单列表范围", orders, "isolated")
        self.check("SCOPE-LIST-IDS", "专用供应商只看到本次订单", [row["id"] for row in visible_orders] == [order["id"]])
        warnings = self.get("VERIFY-WARNING", "检查拒收差异事件预警", "/api/v1/system/warnings")
        matching = [row for row in warnings if row["warning_type"] == "RECEIPT_VARIANCE" and
                    row["target_type"] == "RECEIPT" and row["target_id"] == first["id"]]
        self.check("VERIFY-WARNING-COUNT", "首批拒收只产生一条差异预警", len(matching) == 1)
        self.inventory("STOCK-COMPLETED", 10)
        logs = self.get("VERIFY-AUDIT", "读取本次关键操作审计", "/api/v1/system/audit-logs?size=100", "admin")["items"]
        actions = {(row["action_code"], row["target_id"]) for row in logs}
        required = {("CREATE_DEMAND", demand["id"]), ("CREATE_PLAN", plan["id"]), ("PLAN_APPROVE", plan["id"]),
                    ("GENERATE_ORDER", order["id"]), ("POST_RECEIPT", first["id"]), ("POST_RECEIPT", second["id"]),
                    ("RECONCILIATION_COMPLETE", recon["id"]), ("ORDER_COMPLETED", order["id"])}
        self.check("VERIFY-AUDIT-ACTIONS", "需求、审批、订单、两批收货及对账完成均可追溯", required <= actions)

    def finish(self):
        if self.original_orders is not None:
            try:
                after = self.get("PRESERVE-ORDER-READ", "回读原有订单", "/api/v1/procurement/orders")
                ids = {row["id"] for row in self.original_orders}
                before = sorted(self.original_orders, key=lambda row: row["id"])
                preserved = sorted([row for row in after if row["id"] in ids], key=lambda row: row["id"])
                self.check("PRESERVE-ORDERS", "原有演示订单内容未变化", before == preserved,
                           records=len(before), before_sha256=self.digest(before), after_sha256=self.digest(preserved))
            except AcceptanceFailure:
                pass
        if self.original_inventory is not None:
            try:
                after = self.get("PRESERVE-STOCK-READ", "回读原有库存", "/api/v1/master-data/inventory")
                ids = {row["id"] for row in self.original_inventory}
                before = sorted(self.original_inventory, key=lambda row: row["id"])
                preserved = sorted([row for row in after if row["id"] in ids], key=lambda row: row["id"])
                self.check("PRESERVE-INVENTORY", "原有演示库存内容未变化", before == preserved,
                           records=len(before), before_sha256=self.digest(before), after_sha256=self.digest(preserved))
            except AcceptanceFailure:
                pass
        if self.temp_user:
            try:
                result = self.api("CLEANUP-USER", "禁用临时验收账号并保留审计记录", "admin", "PATCH",
                                  f"/api/v1/admin/users/{self.temp_user['id']}",
                                  {"expectedVersion": self.temp_user["version"], "enabled": False})
                self.check("CLEANUP-USER-STATE", "临时验收账号已禁用", result["enabled"] is False)
                self.api("AUTH-REVOKED-TOKEN", "账号禁用后已签发令牌立即失效", "isolated", "GET",
                         "/api/v1/auth/me", expected=401, error_code="UNAUTHORIZED")
            except AcceptanceFailure:
                pass
        self.tokens.clear()
        output_dir = self.args.output_dir.resolve()
        output_dir.mkdir(parents=True, exist_ok=True)
        failed = sum(check["status"] == "FAIL" for check in self.checks)
        result = {"schema_version": 1, "kind": "automated_mysql_api_acceptance", "run_id": self.run_id,
                  "base_url": self.base_url, "started_at": self.started_at,
                  "finished_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                  "status": "FAIL" if failed else "PASS", "total_checks": len(self.checks),
                  "passed_checks": len(self.checks) - failed, "failed_checks": failed,
                  "limitations": ["自动化 API 验收不代表用户个人 UAT 已完成。",
                                  "新建 UAT 业务记录保留以供追溯；临时供应商账号在结束时禁用。",
                                  "未模拟并发请求、外部云环境、Docker Compose 或真实企业数据。"],
                  "created_resources": self.created, "checks": self.checks}
        path = output_dir / f"mysql-uat-{self.run_id}.json"
        path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{result['status']}: {result['passed_checks']}/{result['total_checks']} checks; report: {path}")
        return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parents[1] / "output" / "acceptance")
    for role in ("admin", "buyer", "supplier", "manager"):
        parser.add_argument(f"--{role}-user", default=role)
    args = parser.parse_args()
    url = urllib.parse.urlsplit(args.base_url)
    if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password or url.query or url.fragment or url.path not in ("", "/"):
        parser.error("--base-url must be a plain HTTP(S) origin without credentials, query, or path")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    passwords = {}
    for role in ("admin", "buyer", "supplier", "manager"):
        value = os.environ.get("SCIC_UAT_" + role.upper() + "_PASSWORD") or os.environ.get("SCIC_UAT_PASSWORD")
        if not value:
            if not sys.stdin.isatty():
                parser.error("Supply SCIC_UAT_PASSWORD or all role-specific password environment variables; values are never logged")
            value = getpass.getpass(role.upper() + " password: ")
        passwords[role] = value
    acceptance = Acceptance(args)
    try:
        acceptance.run(passwords)
    except AcceptanceFailure:
        pass
    except KeyboardInterrupt:
        acceptance.checks.append({"id": "INTERRUPTED", "title": "验收被中断", "status": "FAIL"})
        print("FAIL INTERRUPTED")
    except Exception as exc:
        # Exception messages can contain response fragments: save only the type.
        acceptance.checks.append({"id": "UNEXPECTED", "title": "验收执行异常", "status": "FAIL", "failure_type": type(exc).__name__})
        print("FAIL UNEXPECTED: " + type(exc).__name__)
    finally:
        passwords.clear()
    return acceptance.finish()


if __name__ == "__main__":
    sys.exit(main())
