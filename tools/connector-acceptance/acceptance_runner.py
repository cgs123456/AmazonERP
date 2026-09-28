#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AmazonERP 连接器验收 runner（runbook §3，P0-52c）。

用途
----
凭证到位当天，用**一条命令**把「有 API 就能直接用」从人工声明变成机器可判定的联调记录：

    python tools/connector-acceptance/acceptance_runner.py \
        --service-url http://127.0.0.1:8096 --connector spapi --shop-id 1001 \
        --marketplace-id ATVPDKIKX0DER \
        --operations orders,inventory,feeds,reports,reports-download,finances,fees \
        --out-dir ./acceptance-out

产出 `connector-acceptance-<connector>-<yyyyMMddHHmmss>.json` + 同名 `.json.sha256`。

诚实边界（**必读，不得跳过**）
--------------------------
1. 本 runner 只与被测 **AmazonERP 服务**（`amz-service-spapi`）通话，**不直接调用 Amazon**，
   也**不接触任何平台凭证**——因此它不可能伪造平台侧证据，同时也不可能凭自己产出 A5 证据：
   记录里的平台字段全部来自被测服务的响应文本（P0-52a 收敛后的诊断文本）。
2. 只记录**真实观察到**的东西。凡是本 runner 观察不到的（两店 token 摘要、启动自检拒绝、
   `x-amzn-RateLimit-Limit` 回填窗口），一律要求 `--attest` 提供**已脱敏**的证据文件，
   否则对应标准判不通过（E0）而不是猜一个值。
3. 证据不足时**不会**写成好结论：`conclusion.displayText` 只取
   `ConnectorEvidencePolicy.Assessment.displayText()` 的三种取值之一。
4. 退出码：0 = 全部达标；1 = 已执行但有缺项；2 = 环境不可用 / 前置不满足（**不用 0 表示跳过**）。

安全
----
- 读到的任何文本先过 `redact()`（与 Java `com.amz.connector.ErrorSummary` 同口径），
  渲染后再做一次机密扫描；命中即在记录里掩掉并置 `conclusion.secretScan`，该记录判为不合格。
- JWT（`token` 头）只从 `--auth-token-file` 读，永不写入记录、永不打印。
- 本文件自身 sha256 写入 `generator.sha256`，使记录与 runner 版本可绑定。
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SCHEMA_VERSION = 'connector-acceptance/1'
RUNNER_REL_PATH = 'tools/connector-acceptance/acceptance_runner.py'
DEFAULT_CONFIG_FILE = 'amz-service/amz-service-spapi/src/main/resources/application-prod.yml'
DEFAULT_REPORT_TYPE = 'GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE'
DEFAULT_ABSENT_SHOP_ID = 999999999
DEFAULT_FEED_CONTENT = json.dumps({
    'header': {'sellerId': 'PLACEHOLDER', 'version': '2.0', 'issueLocale': 'en_US'},
    'messages': [{'messageId': 1, 'operationType': 'UPDATE',
                  'productType': 'LUGGAGE', 'attributes': {}}],
})

EXIT_PASS = 0
EXIT_GAPS = 1
EXIT_PRECONDITION = 2

# 与 ConnectorEvidencePolicy.Criterion 一一对应的必需等级（runner 侧唯一事实源之一，
# 漂移由 tools/connector-acceptance/selftest.py 的 key 断言与文档对照锁死）。
CRITERIA_REQUIRED = {
    'A1': 4, 'A2': 3, 'A3': 4, 'A4': 4, 'A5': 4, 'A6': 3, 'A7': 4, 'A8': 4,
}
LEVEL_RANK = {'E0': 0, 'E1': 1, 'E2': 2, 'E3': 3, 'E4': 4, 'E5': 5}
DISPLAY_READY = 'API-Ready（已联调）'
DISPLAY_REACHABLE = '已接通（联调中）'
DISPLAY_CAPABLE = '具备对接能力（未联调）'
TERMINAL_REPORT_STATUS = {'DONE', 'FATAL', 'CANCELLED'}


class PreconditionError(Exception):
    """前置不满足（退出码 2）。消息会打印到 stderr，**不得**含机密。"""


class TransportError(Exception):
    """一次 HTTP 调用失败（连接层），由调用方决定是前置失败还是记为该 operation 失败。"""


# --------------------------------------------------------------------------------------
# 脱敏（与 com.amz.connector.ErrorSummary 同口径，顺序有语义）
# --------------------------------------------------------------------------------------

REDACTED = '***'

# 整段掩 Authorization 头值（SigV4 的 Credential=…/Signature=… 或 Bearer <token>）。
_AUTHORIZATION_HEADER = re.compile(r'(?i)\b(authorization)(\s*[=:]\s*)[^\r\n]+')

# 键值对脱敏：S3 预签名参数、LWA 令牌、AWS/LWA 密钥、口令（值兼容带引号与裸值）。
_SECRET_PAIR = re.compile(
    r'(?i)(x-amz-signature|x-amz-credential|x-amz-security-token|x-amz-access-token'
    r'|authorization|credential|signature|access_token|refresh_token|refreshtoken'
    r'|client_secret|clientsecret|access_key|accesskey|secret_key|secretkey'
    r'|session_token|sessiontoken|password)'
    r'(\s*[=:]\s*)("[^"]*"|\'[^\']*\'|[^\s&,;"\']+)')

# 裸 Bearer / Basic 令牌。
_BEARER = re.compile(r'(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]+')

# 渲染后二次扫描的高置信度机密特征（命中即判该记录不合格，且掩掉）。
# 命中即「值本身是机密」。带 `lead` 命名组的模式语义是：**键名保留、只掩值**，
# 所以 (?!\*\*\*) 让已被 redact() 掩过的 `x-amz-signature=***` 不再误报
# （误报会把合法的 P0-53 诊断文本整条记录判成不合格）。
_LEAK_PATTERNS = (
    ('aws_access_key_id', re.compile(r'\bAKIA[0-9A-Z]{16}\b')),
    ('presigned_signature',
     re.compile(r'(?i)(?P<lead>x-amz-signature\s*[=:]\s*)(?!\*\*\*)[^\s&,;]+')),
    ('presigned_credential',
     re.compile(r'(?i)(?P<lead>x-amz-credential\s*[=:]\s*)(?!\*\*\*)[^\s&,;]+')),
    ('lwa_refresh_token',
     re.compile(r'(?i)(?:Atz[ar](?:%7C|\|)|amzn1\.oa2\.)[A-Za-z0-9._%|~-]+')),
    ('jwt', re.compile(r'\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.')),
    ('pem_private_key', re.compile(r'-----BEGIN [A-Z ]*PRIVATE KEY-----')),
)


def _leak_mask(match):
    """掩掉命中片段；带 `lead` 命名组的模式（预签名参数）保留键名，只掩掉值。"""
    lead = match.groupdict().get('lead')
    return (lead + REDACTED) if lead is not None else REDACTED


def redact(raw):
    """掩掉签名 / 令牌 / 密钥类键值；其余文本保持不变（含 status= / errors[].code）。"""
    if raw is None or raw == '':
        return raw
    text = str(raw)
    text = _AUTHORIZATION_HEADER.sub(lambda m: m.group(1) + m.group(2) + REDACTED, text)
    text = _SECRET_PAIR.sub(lambda m: m.group(1) + m.group(2) + REDACTED, text)
    text = _BEARER.sub(lambda m: m.group(1) + ' ' + REDACTED, text)
    return text


def mark_untrusted(value, hits):
    """把命中机密特征的值掩掉并登记（返回值本身，hits 就地追加路径描述）。"""
    if isinstance(value, str):
        cleaned = value
        for name, pattern in _LEAK_PATTERNS:
            if pattern.search(cleaned):
                hits.append(name)
                cleaned = pattern.sub(_leak_mask, cleaned)
        return cleaned
    if isinstance(value, dict):
        return {k: mark_untrusted(v, hits) for k, v in value.items()}
    if isinstance(value, list):
        return [mark_untrusted(v, hits) for v in value]
    return value


def mask_seller_id(seller_id):
    """sellerId 打码：保留前 4 位 + `****`（runbook §4.6）。"""
    if not seller_id:
        return None
    text = str(seller_id)
    return (text[:4] + '****') if len(text) > 4 else '****'


# --------------------------------------------------------------------------------------
# 传输层（stdlib urllib；不设任何第三方依赖）
# --------------------------------------------------------------------------------------

class ServiceClient:
    """被测 AmazonERP 服务的 HTTP 客户端。

    `/spapi/**` 受 `BaseAuthInterceptor` 保护，必须携带 `token` 头（用户 JWT）。
    JWT 仅保存在内存里，出现在任何记录或日志之前一律掩成 `***`。
    """

    def __init__(self, base_url, token=None, timeout_s=30.0):
        self.base_url = base_url.rstrip('/')
        self._token = token
        self.timeout_s = timeout_s

    def header_names(self):
        names = ['accept']
        if self._token:
            names.append('token')
        return names

    def call(self, method, path, query=None, body=None):
        """发一次请求 → (http_status, text, duration_ms)。连接层失败抛 TransportError。"""
        url = self.base_url + path
        if query:
            url = url + '?' + urllib.parse.urlencode(query, doseq=True)
        data = None
        if body is not None:
            data = json.dumps(body).encode('utf-8')
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header('Accept', 'application/json')
        if self._token:
            request.add_header('token', self._token)
        if data is not None:
            request.add_header('Content-Type', 'application/json')

        started = time.monotonic()
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_s) as response:
                text = response.read().decode('utf-8', 'replace')
                status = response.status
        except urllib.error.HTTPError as error:
            text = error.read().decode('utf-8', 'replace')
            status = error.code
        except urllib.error.URLError as error:
            raise TransportError(redact(str(error.reason)))
        except OSError as error:
            raise TransportError(redact(str(error)))
        duration_ms = int((time.monotonic() - started) * 1000)
        return status, text, duration_ms


def parse_result(text):
    """把服务响应当作 `com.amz.result.Result` 解析；非 JSON 时如实记为裸文本。"""
    try:
        payload = json.loads(text)
    except (ValueError, TypeError):
        return {'resultCode': None, 'message': redact(text)[:1000], 'data': None, 'raw': True}
    if not isinstance(payload, dict):
        return {'resultCode': None, 'message': redact(str(payload))[:1000], 'data': None, 'raw': True}
    return {
        'resultCode': payload.get('code'),
        'message': redact(payload.get('message')),
        'data': payload.get('data'),
        'hiddenFields': payload.get('_hiddenFields'),
        'raw': False,
    }


_STATUS_RE = re.compile(r'\bstatus=(-?\d{1,3})\b')
_BODY_RE = re.compile(r'\bbody=(\{.*)', re.S)
_PATH_RE = re.compile(r'\bpath=([^\s]+)')


def parse_platform_error(message):
    """从诊断文本里抽取平台字段（best-effort）。

    文本形态来自 `OrdersClient` / `FeedsClient` / `SpApiGateway`：
        `fetchOrders failed shopId=1001 status=404 body={"errors":[{"code":"NotFound",…}]}`
    注意：这是**文本解析**，不是结构化错误契约（P0-52a 未实现的正是后者）。
    `ErrorSummary` 会在 1000 字符处截断（尾随 `…`），截断时如实标注。
    """
    if not message:
        return {'status': None, 'code': None, 'message': None, 'truncated': False}
    out = {'status': None, 'code': None, 'message': None, 'truncated': message.endswith('…')}
    status_match = _STATUS_RE.search(message)
    if status_match:
        try:
            out['status'] = int(status_match.group(1))
        except ValueError:
            out['status'] = None
    body_match = _BODY_RE.search(message)
    if body_match:
        raw = body_match.group(1)
        if out['truncated'] and raw.endswith('…'):
            raw = raw[:-1]
        try:
            body = json.loads(raw) if not out['truncated'] else None
        except ValueError:
            body = None
        if isinstance(body, dict):
            errors = body.get('errors')
            if isinstance(errors, list) and errors and isinstance(errors[0], dict):
                out['code'] = errors[0].get('code')
                out['message'] = errors[0].get('message')
            elif body:
                out['code'] = None
                out['message'] = None
        if out['code'] is None and out['truncated']:
            code_match = re.search(r'"code"\s*:\s*"([^"]+)"', raw)
            message_match = re.search(r'"message"\s*:\s*"([^"]*)"', raw)
            if code_match:
                out['code'] = code_match.group(1)
            if message_match:
                out['message'] = message_match.group(1)
    return out


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as handle:
        for chunk in iter(lambda: handle.read(65536), b''):
            digest.update(chunk)
    return digest.hexdigest()

# ======================================================================================
# 运行计划与记录骨架（runbook §3.3 / §4.2）
# ======================================================================================

#: `--operations` 的合法取值（元组顺序 = 执行顺序）。
ALL_OPERATIONS = ('orders', 'inventory', 'feeds', 'reports', 'reports-download', 'finances', 'fees')

#: `Result.failure` 一律返回 **HTTP 200 + code=400**（amz-common `Result`），
#: 因此「operation 是否成功」只能按 `resultCode == 200` 判定，**不能**按 HTTP 状态码判定。
# 需要 marketplaceId 的 operation（其余可省略 --marketplace-id）。
OPERATIONS_REQUIRING_MARKETPLACE = ('orders', 'reports')

SERVICE_SUCCESS_CODE = 200

#: 沙箱限流 5 rps（runbook §1.1）→ runner 自身必须串行且两次真实调用间隔 ≥200ms。
MIN_INTERVAL_S = 0.2

#: §3.3 冻结的键集合。新增键必须同步更新本常量、`--selftest`、`selftest.py` 与 runbook §3.3。
REPORT_TOP_LEVEL_KEYS = (
    'schemaVersion', 'connector', 'generatedAt', 'generator', 'target', 'identity',
    'operations', 'errorCodes', 'rateLimit', 'documentChain', 'criteria', 'conclusion',
)
OPERATION_REQUIRED_KEYS = ('name', 'method', 'path', 'httpStatus', 'durationMs', 'request', 'response')
OPERATION_ALLOWED_EXTRA_KEYS = ('ok', 'observer', 'notes')
RESPONSE_REQUIRED_KEYS = ('payloadKeys', 'itemCount')
RESPONSE_ALLOWED_EXTRA_KEYS = ('resultCode', 'message', 'shape', 'values')
CRITERION_KEYS = ('A1', 'A2', 'A3', 'A4', 'A5', 'A6', 'A7', 'A8')
ATTESTATION_SCHEMA_VERSION = 'connector-acceptance-attestation/1'

#: Outbox 安全视图契约。null 字段也必须出现在 JSON 中；缺字段/多出敏感字段都判契约不符。
OUTBOX_VIEW_REQUIRED_KEYS = (
    'id', 'shopId', 'operationId', 'httpMethod', 'requestPath', 'status',
    'attemptCount', 'maxAttempts', 'responseStatus', 'marketplaceId',
    'responseRequestId', 'lastErrorCode', 'lastErrorMessage', 'createdAt',
    'updatedAt', 'completedAt', 'expectedStatuses', 'rateLimitVariant',
)
OUTBOX_STATUSES = ('PENDING', 'SUCCEEDED', 'FAILED', 'REPLAYING', 'REPLAYED', 'DLQ')
OUTBOX_REPLAYABLE_STATUSES = ('FAILED', 'DLQ')
OUTBOX_SUCCESS_TERMINAL_STATUSES = ('REPLAYED', 'SUCCEEDED')
OUTBOX_FORBIDDEN_KEYS = frozenset({
    'requestquery', 'requestbody', 'requestbodyencrypted', 'responsebody',
    'responsebodyencrypted', 'ciphertext', 'secret', 'credentials',
})
REPLAY_RESULT_REQUIRED_KEYS = ('success', 'outcome', 'status', 'message')

#: operation → (被测服务触发端点, 对应平台端点)。
#: 平台端点取自客户端常量（`OrdersClient.ORDERS_PATH` 等）；runner **观察不到出站请求**，
#: 故只写成 `documentedPlatformEndpoint`（有据可查的映射），**不得**当成实测字段。
OPERATION_PLAN = (
    ('orders', 'POST /spapi/sync/orders?shopId=', 'GET /orders/v0/orders'),
    ('inventory', 'POST /spapi/inventory/sync/{shopId}', 'GET /fba/inventory/v1/summaries'),
    ('feeds', 'POST /spapi/feeds/submit + GET /spapi/feeds/status/{shopId}/{feedId}',
     'POST /feeds/2021-06-30/documents + POST /feeds/2021-06-30/feeds'),
    ('reports', 'POST /spapi/finance/report/request + GET /spapi/finance/report/{reportId}',
     'POST /reports/2021-06-30/reports + GET /reports/2021-06-30/reports/{reportId}'),
    ('reports-download', 'GET /spapi/finance/document/{documentId}',
     'GET /reports/2021-06-30/documents/{reportDocumentId} + S3 预签名下载'),
    ('finances', 'GET /spapi/finance/events?shopId=', 'GET /finances/v0/financialEvents'),
    ('fees', 'POST /spapi/finance/fees/estimate?shopId=&...', 'POST /products/fees/v0/feesEstimate'),
)

#: 四类错误码（runbook §0 C3 / §4.3）：401/403/429 需要专用店铺（`--error-shop-*`）；
#: 404 由 runner 自造（不存在的 reportId / feedId），不依赖任何额外准备。
EXPECTED_ERROR_CODES = (401, 403, 404, 429)


def shape_of(value, depth=0):
    """只描述结构（键名 / 条数 / 字符数），**永不回显字符串内容** —— PII 与密钥防线。

    例外（白名单）：`reportId` / `documentId` / `feedId` / `processingStatus` /
    `resultFeedDocumentId` 按 runbook §4.6 属「非秘密且取证必需」，原样保留。
    """
    if isinstance(value, dict):
        out = {'type': 'object', 'keys': sorted(str(key) for key in value.keys())[:40]}
        if depth == 0:
            for key in ('reportId', 'documentId', 'feedId',
                        'processingStatus', 'resultFeedDocumentId'):
                item = value.get(key)
                if isinstance(item, str):
                    out[key] = item
        return out
    if isinstance(value, list):
        out = {'type': 'array', 'itemCount': len(value)}
        if value and depth == 0:
            out['itemShape'] = shape_of(value[0], depth + 1)
        return out
    if isinstance(value, str):
        return {'type': 'string', 'charCount': len(value)}
    if value is None or isinstance(value, bool):
        return {'type': 'null' if value is None else 'boolean', 'value': value}
    if isinstance(value, (int, float)):
        return {'type': 'number', 'value': value}
    return {'type': type(value).__name__}


def query_string(query):
    """请求查询串（脱敏后）。shopId / marketplaceId 非秘密，token 不在此处。"""
    if not query:
        return None
    items = {key: value for key, value in query.items() if value is not None}
    if not items:
        return None
    return redact(urllib.parse.urlencode(items))


def quote_path(value):
    """把 reportId / feedId / documentId 等路径片段安全编码（防注入到路径里）。"""
    return urllib.parse.quote(str(value), safe='')


def observed_headers(client):
    """记录**实际发出的请求头名**（token 值一律 `***`，见 runbook C2）。"""
    headers = {}
    for name in client.header_names():
        headers[name] = REDACTED if name == 'token' else 'application/json'
    return headers


def _is_int(value):
    """bool 是 int 的子类，但 JSON 布尔值不能冒充整数 ID/状态码。"""
    return isinstance(value, int) and not isinstance(value, bool)


def _forbidden_outbox_keys(payload):
    if not isinstance(payload, dict):
        return []
    return sorted(str(key) for key in payload if str(key).lower() in OUTBOX_FORBIDDEN_KEYS)


def outbox_view_errors(item):
    """校验单条 Outbox 安全视图；返回错误列表，空列表表示契约成立。"""
    if not isinstance(item, dict):
        return ['Outbox 列表项不是 JSON 对象']
    errors = []
    missing = [key for key in OUTBOX_VIEW_REQUIRED_KEYS if key not in item]
    if missing:
        errors.append('缺字段：' + ','.join(missing))
    forbidden = _forbidden_outbox_keys(item)
    if forbidden:
        errors.append('含禁止外泄字段：' + ','.join(forbidden))
    if not _is_int(item.get('id')) or item.get('id') <= 0:
        errors.append('id 必须是正整数')
    if not _is_int(item.get('shopId')) or item.get('shopId') <= 0:
        errors.append('shopId 必须是正整数')
    if not isinstance(item.get('operationId'), str) or not item.get('operationId').strip():
        errors.append('operationId 必须是非空字符串')
    if item.get('httpMethod') not in ('GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE'):
        errors.append('httpMethod 非法：' + repr(item.get('httpMethod')))
    if not isinstance(item.get('requestPath'), str) or not item.get('requestPath').startswith('/'):
        errors.append('requestPath 必须是 / 开头的路径（不得含查询串）')
    elif '?' in item.get('requestPath'):
        errors.append('requestPath 不得包含查询串')
    if item.get('status') not in OUTBOX_STATUSES:
        errors.append('status 非法：' + repr(item.get('status')))
    for key in ('attemptCount', 'maxAttempts'):
        if item.get(key) is not None and not _is_int(item.get(key)):
            errors.append(key + ' 必须是整数或 null')
    if item.get('maxAttempts') is not None and item.get('maxAttempts') < 1:
        errors.append('maxAttempts 必须 >= 1')
    if item.get('responseStatus') is not None and not _is_int(item.get('responseStatus')):
        errors.append('responseStatus 必须是整数或 null')
    expected = item.get('expectedStatuses')
    if not isinstance(expected, list) or not expected or any(not _is_int(code) for code in expected):
        errors.append('expectedStatuses 必须是非空整数数组')
    for key in ('marketplaceId', 'responseRequestId', 'lastErrorCode', 'lastErrorMessage',
                'createdAt', 'updatedAt', 'completedAt', 'rateLimitVariant'):
        if item.get(key) is not None and not isinstance(item.get(key), str):
            errors.append(key + ' 必须是字符串或 null')
    return errors


def outbox_replay_precondition(item):
    """只有真实 429/5xx 且处于 FAILED/DLQ 的记录才允许作为 E4 重放目标。"""
    errors = outbox_view_errors(item)
    if errors:
        return False, '目标记录安全视图契约不符：' + '; '.join(errors)
    if item.get('status') not in OUTBOX_REPLAYABLE_STATUSES:
        return False, '目标记录 status={0}，不是 FAILED/DLQ'.format(item.get('status'))
    response_status = item.get('responseStatus')
    if not (_is_int(response_status) and (response_status == 429 or 500 <= response_status <= 599)):
        return False, '目标记录 responseStatus={0}，不是 429/5xx'.format(response_status)
    return True, None


def find_outbox_item(items, item_id):
    for item in items:
        if isinstance(item, dict) and item.get('id') == item_id:
            return item
    return None


def replay_result_errors(payload):
    """校验 POST /outbox/{id}/replay 的 Result.data 安全结构。"""
    if not isinstance(payload, dict):
        return ['ReplayResult 不是 JSON 对象']
    errors = []
    missing = [key for key in REPLAY_RESULT_REQUIRED_KEYS if key not in payload]
    if missing:
        errors.append('缺字段：' + ','.join(missing))
    forbidden = _forbidden_outbox_keys(payload)
    if forbidden:
        errors.append('含禁止外泄字段：' + ','.join(forbidden))
    if not isinstance(payload.get('success'), bool):
        errors.append('success 必须是布尔值')
    for key in ('outcome', 'status'):
        if payload.get(key) is not None and not isinstance(payload.get(key), str):
            errors.append(key + ' 必须是字符串或 null')
    if payload.get('message') is not None and not isinstance(payload.get('message'), str):
        errors.append('message 必须是字符串或 null')
    return errors


class Recorder:
    """把被测服务调用变成 §3.3 的 `operations` 记录，并强制串行 + ≥200ms 间隔。

    `Result.failure` 是 **HTTP 200 + code=400**，所以判成功只看 `resultCode`。
    """

    def __init__(self, client, echo=True):
        self.client = client
        self.echo = echo
        self.operations = []
        self._last_call_at = None

    def _pace(self):
        if self._last_call_at is None:
            return
        remaining = MIN_INTERVAL_S - (time.monotonic() - self._last_call_at)
        if remaining > 0:
            time.sleep(remaining)

    def raw_call(self, method, path, query=None, body=None):
        """节流后发一次请求；连接层失败抛 TransportError。"""
        self._pace()
        try:
            status, text, duration_ms = self.client.call(method, path, query=query, body=body)
        finally:
            self._last_call_at = time.monotonic()
        if self.echo:
            suffix = ('?' + query_string(query)) if query else ''
            print('    {0} {1}{2} -> HTTP {3} ({4}ms)'.format(method, path, suffix, status, duration_ms))
        return status, text, duration_ms

    def observe(self, name, method, path, query=None, body=None,
                platform_endpoint=None, keep_values=None):
        """调用一次并登记一条 operation 记录；返回 (记录, 解析后的 Result)。"""
        status, text, duration_ms = self.raw_call(method, path, query=query, body=body)
        parsed = parse_result(text)
        data = parsed.get('data')
        record = {
            'name': name,
            'method': method,
            'path': path,
            'httpStatus': status,
            'durationMs': duration_ms,
            'request': {
                'query': query_string(query),
                'headers': observed_headers(self.client),
                'documentedPlatformEndpoint': platform_endpoint,
            },
            'response': {
                'payloadKeys': sorted(str(key) for key in data.keys())[:40]
                                if isinstance(data, dict) else None,
                'itemCount': len(data) if isinstance(data, list) else None,
                'resultCode': parsed.get('resultCode'),
                'message': parsed.get('message'),
                'shape': shape_of(data),
            },
            'ok': bool(status == 200 and parsed.get('resultCode') == SERVICE_SUCCESS_CODE),
            'observer': 'runner->amz-service-spapi',
        }
        if keep_values:
            record['response']['values'] = keep_values
        self.operations.append(record)
        return record, parsed


def load_attestation(path):
    """加载 operator 提供的**已脱敏**证据文件；含明文机密或结构不符即拒绝继续（退出码 2）。

    本 runner 只能观测被测服务的进出口，观测不到：两条店铺 token 的差异（A4）、
    启动被拒绝的两个场景（A3 前两例）、`x-amzn-RateLimit-Limit` 回填窗口（A8）。
    这些必须由 operator 提供证据文件，否则对应标准判 E0，**不得**猜测。
    """
    if not path:
        return None
    try:
        with open(path, 'r', encoding='utf-8') as handle:
            raw = handle.read()
    except OSError as error:
        raise PreconditionError('attestation 读取失败：{0}'.format(redact(str(error))))
    try:
        payload = json.loads(raw)
    except ValueError as error:
        raise PreconditionError('attestation 不是合法 JSON：{0}'.format(redact(str(error))))
    if not isinstance(payload, dict):
        raise PreconditionError('attestation 顶层必须是 JSON 对象')
    version = payload.get('schemaVersion')
    if version != ATTESTATION_SCHEMA_VERSION:
        raise PreconditionError('attestation.schemaVersion 必须是 {0}，实际为 {1}'.format(
            ATTESTATION_SCHEMA_VERSION, redact(str(version))))
    hits = []
    mark_untrusted(raw, hits)
    if hits:
        raise PreconditionError('attestation 含未脱敏机密（{0}），拒绝继续：请先按 runbook §4.6 脱敏'
                                .format(','.join(sorted(set(hits)))))
    return payload


def attestation_section(attestation, *keys):
    """按路径安全取值；任一层缺失返回 None（不抛异常，由调用方记 blocker）。"""
    node = attestation
    for key in keys:
        if not isinstance(node, dict):
            return None
        node = node.get(key)
    return node

# ======================================================================================
# 一次验收执行
# ======================================================================================

class AcceptanceRun:
    """一次验收执行的上下文：判定只能引用这里被**真实观测**到的字段。

    operation handler 统一返回
    `{'ok': bool, 'records': [operation 名], 'blockers': [str], 'platformCallDocumented': bool}`。
    `platformCallDocumented` 表示该端点在代码里确实会出站到平台（由 `OPERATION_PLAN` 记录），
    **不表示** runner 观测到了出站流量——runner 只能看见被测服务的进出口。
    """

    def __init__(self, recorder, options, attestation, self_description):
        self.recorder = recorder
        self.options = options
        self.attestation = attestation or {}
        self.self_description = self_description or {}
        self.operations = {}
        self.error_codes = []
        self.rate_limit = []
        self.document_chain = {}
        self.feed = {}
        self.report = {}
        self.checks = []
        self.notes = []
        self.connectors_probe = {}
        self.outbox_probe = {}
        self.outbox_replay_probe = {}
        self.stub_self_declared = self.self_description.get('stub') is True

    # ------------------------------------------------------------------ 调度
    def run_operations(self):
        for name in self.options.operations:
            handler = getattr(self, 'op_' + name.replace('-', '_'), None)
            if handler is None:
                self.operations[name] = {'ok': False, 'records': [], 'blockers': ['未实现'], 'platformCallDocumented': False}
                continue
            print('  [operation] {0}'.format(name))
            try:
                outcome = handler()
            except TransportError as error:
                outcome = {'ok': False, 'records': [],
                           'blockers': ['连接层失败：{0}'.format(error)],
                           'platformCallDocumented': True}
            self.operations[name] = outcome
            print('    => ok={0}{1}'.format(
                outcome['ok'],
                ('' if not outcome['blockers'] else ' blockers=' + '; '.join(outcome['blockers']))))

    # ------------------------------------------------------------- 错误码覆盖
    def collect_error_codes(self):
        """runbook §4.3：401/403/404/429 四类。缺失即记「未匹配」，**绝不猜值**。"""
        print('  [errorCodes] 401/403/404/429')
        if not self.options.error_shop_401:
            self.error_codes.append(self._entry(
                401, 'orders.fetchOrders',
                '未提供 --error-shop-401（缺可构造平台 401 的店铺：已吊销 refresh token 或伪造 access_token）'))
        else:
            self._error_auth(401, self.options.error_shop_401)
        if not self.options.error_shop_403:
            self.error_codes.append(self._entry(
                403, 'reports.getReport',
                '未提供 --error-shop-403（缺可构造平台 403 的店铺：授权 scope 不含目标 operation）'))
        else:
            self._error_auth(403, self.options.error_shop_403)
        self._error_404()
        self._error_429(self.options.error_shop_429)

    @staticmethod
    def _entry(expected, operation, how_constructed):
        return {
            'expected': expected,
            'operation': operation,
            'platformStatus': None,
            'platformCode': None,
            'platformMessage': None,
            'howConstructed': how_constructed,
            'evidence': None,
            'serviceHttpStatus': None,
            'serviceResultCode': None,
            'matched': False,
        }

    def _record_error(self, expected, operation, record, parsed, how_constructed):
        message = parsed.get('message') or ''
        detail = parse_platform_error(message)
        entry = self._entry(expected, operation, how_constructed)
        entry['platformStatus'] = detail['status']
        entry['platformCode'] = redact(detail['code'])
        entry['platformMessage'] = redact(detail['message'])
        entry['platformMessageTruncated'] = bool(detail['truncated'])
        entry['evidence'] = message[:600] if message else None
        entry['serviceHttpStatus'] = record['httpStatus']
        entry['serviceResultCode'] = parsed.get('resultCode')
        entry['matched'] = detail['status'] == expected
        self.error_codes.append(entry)
        print('    {0}: platformStatus={1} platformCode={2} matched={3}'.format(
            expected, entry['platformStatus'], entry['platformCode'], entry['matched']))
        return entry

    def _error_auth(self, expected, shop_id):
        """401/403 必须来自**平台**，因此要走真实出站路径（orders），不能本地伪造。"""
        try:
            record, parsed = self.recorder.observe(
                'orders.syncOrders(error-{0})'.format(expected), 'POST', '/spapi/sync/orders',
                query={'shopId': shop_id}, platform_endpoint='GET /orders/v0/orders')
        except TransportError as error:
            entry = self._entry(expected, 'orders.fetchOrders',
                                '--error-shop-{0}={1}；调用失败：{2}'.format(expected, shop_id, error))
            self.error_codes.append(entry)
            return
        self._record_error(expected, 'orders.fetchOrders', record, parsed,
                           '用 --error-shop-{0}={1} 指定的店铺触发平台 {0}（推定的失效凭证 / 缺 scope 店铺）'
                           .format(expected, shop_id))

    def _error_404(self):
        """404 由 runner 自造：请求不存在的 reportId / feedId，只读、不新增任何资源。"""
        attempts = (
            ('reports.getReport(missing)', 'GET',
             '/spapi/finance/report/' + quote_path(self.options.missing_report_id),
             {'shopId': self.options.shop_id},
             'GET /reports/2021-06-30/reports/{reportId}', 'reports.getReport',
             '用不存在的 reportId={0} 触发平台 404'.format(self.options.missing_report_id)),
            ('feeds.status(missing)', 'GET',
             '/spapi/feeds/status/' + quote_path(self.options.shop_id) + '/'
             + quote_path(self.options.missing_feed_id),
             None, 'GET /feeds/2021-06-30/feeds/{feedId}', 'feeds.getFeedStatus',
             '用不存在的 feedId={0} 触发平台 404'.format(self.options.missing_feed_id)),
        )
        for name, method, path, query, endpoint, operation, how in attempts:
            try:
                record, parsed = self.recorder.observe(
                    name, method, path, query=query, platform_endpoint=endpoint)
            except TransportError as error:
                self.error_codes.append(self._entry(
                    404, operation, how + '；调用失败：' + str(error)))
                return
            if self._record_error(404, operation, record, parsed, how)['matched']:
                return

    def _error_429(self, shop_id):
        """429：沙箱 5 rps / burst 15 下，5 rps 稳态不会耗尽令牌桶 → burst 只是**尽力**尝试，
        真正可靠的做法是并发负载或专用店铺；未能观测到即如实记未匹配。"""
        if not shop_id:
            self.error_codes.append(self._entry(
                429, 'orders.fetchOrders',
                '未提供 --error-shop-429；且沙箱 burst=15 时 runner 的 ≥200ms 串行节流无法自造 429'))
            return
        attempts = max(1, self.options.burst_max)
        last = None
        for index in range(1, attempts + 1):
            try:
                record, parsed = self.recorder.observe(
                    'orders.syncOrders(burst-{0})'.format(index), 'POST', '/spapi/sync/orders',
                    query={'shopId': shop_id}, platform_endpoint='GET /orders/v0/orders')
            except TransportError as error:
                self.error_codes.append(self._entry(
                    429, 'orders.fetchOrders',
                    '--error-shop-429={0}；burst 第 {1} 次连接层失败：{2}'.format(shop_id, index, error)))
                return
            detail = parse_platform_error(parsed.get('message') or '')
            last = (record, parsed)
            if detail['status'] == 429:
                self._record_error(
                    429, 'orders.fetchOrders', record, parsed,
                    '在 --error-shop-429={0} 上连续第 {1} 次调用（burst 上限 {2}）观测到平台 429'
                    .format(shop_id, index, attempts))
                return
        record, parsed = last
        entry = self._entry(
            429, 'orders.fetchOrders',
            '--error-shop-429={0}；已连续调用 {1} 次仍未观测到 429（5 rps 稳态不耗尽 burst=15），'
            '需并发负载或改用生产环境构造'.format(shop_id, attempts))
        entry['serviceHttpStatus'] = record['httpStatus']
        entry['serviceResultCode'] = parsed.get('resultCode')
        entry['evidence'] = (parsed.get('message') or '')[:600] or None
        self.error_codes.append(entry)

    # ------------------------------------------------------------- 运行期自检
    def check_absent_shop(self):
        """runbook §4.1 第 3 步：不存在的 shopId 必须**显式失败**，不得空列表 / 0。"""
        absent = self.options.absent_shop_id
        print('  [A2] 缺凭证显式失败（shopId={0}）'.format(absent))
        expected_text = 'no credential for shopId=' + str(absent)
        probes = (
            ('absent-shop.orders', 'POST', '/spapi/sync/orders', {'shopId': absent}),
            ('absent-shop.inventory', 'POST', '/spapi/inventory/sync/' + quote_path(absent), None),
        )
        for name, method, path, query in probes:
            try:
                record, parsed = self.recorder.observe(name, method, path, query=query)
            except TransportError as error:
                self.checks.append({'check': name, 'pass': False, 'expected': expected_text,
                                    'observed': '连接层失败：' + str(error)})
                continue
            message = parsed.get('message') or ''
            explicit = (not record['ok']) and expected_text in message and parsed.get('data') is None
            self.checks.append({
                'check': name,
                'pass': explicit,
                'expected': expected_text,
                'observed': {
                    'serviceHttpStatus': record['httpStatus'],
                    'serviceResultCode': parsed.get('resultCode'),
                    'message': message[:300],
                    'data': shape_of(parsed.get('data')),
                },
            })

    def probe_connectors(self):
        """A6：能力清单端点。不可达或缺少 spapi 条目时如实记 E0 + blocker，不假装通过。"""
        path = self.options.connectors_path
        print('  [A6] GET {0}'.format(path))
        try:
            record, parsed = self.recorder.observe('connectors.list', 'GET', path)
        except TransportError as error:
            self.connectors_probe = {'path': path, 'httpStatus': None,
                                     'observed': '连接层失败：' + str(error)}
            return
        data = parsed.get('data')
        connectors = data.get('connectors') if isinstance(data, dict) else None
        entry = None
        if isinstance(connectors, list):
            for item in connectors:
                if isinstance(item, dict) and item.get('connector') == 'spapi':
                    entry = item
        self.connectors_probe = {
            'path': path,
            'httpStatus': record['httpStatus'],
            'resultCode': parsed.get('resultCode'),
            'connectorEntry': entry,
            'payloadKeys': record['response']['payloadKeys'],
            'message': parsed.get('message'),
        }

    def probe_outbox(self):
        """A7：只读校验 Outbox 安全视图；仅显式 ID 才尝试人工重放。"""
        path = self.options.outbox_path
        limit = self.options.outbox_limit
        print('  [A7] GET {0}?limit={1}'.format(path, limit))
        try:
            record, parsed = self.recorder.observe(
                'outbox.list', 'GET', path, query={'limit': limit})
        except TransportError as error:
            self.outbox_probe = {
                'path': path, 'httpStatus': None, 'resultCode': None,
                'observed': '连接层失败：' + str(error),
            }
            return

        data = parsed.get('data')
        items = data if isinstance(data, list) else []
        contract_errors = []
        if record['httpStatus'] != 200:
            contract_errors.append('HTTP {0}（期望 200）'.format(record['httpStatus']))
        if parsed.get('resultCode') != SERVICE_SUCCESS_CODE:
            contract_errors.append('Result.code={0}（期望 200）'.format(parsed.get('resultCode')))
        if not isinstance(data, list):
            contract_errors.append('data 不是数组')
        elif not data:
            contract_errors.append('data 为空数组，无法验证安全视图字段契约')
        else:
            for index, item in enumerate(data):
                errors = outbox_view_errors(item)
                if errors:
                    contract_errors.append('item[{0}]：{1}'.format(index, '; '.join(errors)))

        statuses = [item.get('status') for item in items if isinstance(item, dict)]
        ids = [item.get('id') for item in items if isinstance(item, dict)]
        self.outbox_probe = {
            'path': path,
            'httpStatus': record['httpStatus'],
            'resultCode': parsed.get('resultCode'),
            'itemCount': len(items),
            'ids': ids[:50],
            'statuses': statuses[:50],
            'contractErrors': contract_errors,
            'responsePayloadKeys': record['response']['payloadKeys'],
            'responseShape': record['response'].get('shape'),
        }

        replay_id = self.options.outbox_replay_id
        if replay_id is None:
            return
        if contract_errors:
            self.outbox_replay_probe = {
                'id': replay_id, 'attempted': False, 'success': False,
                'blocker': 'Outbox 列表契约不成立，拒绝据此重放：' + '; '.join(contract_errors),
            }
            return
        target = find_outbox_item(items, replay_id)
        if target is None:
            self.outbox_replay_probe = {
                'id': replay_id, 'attempted': False, 'success': False,
                'blocker': '显式重放目标 id={0} 不在本次 GET {1}?limit={2} 的结果中；'
                           '请提高 --outbox-limit 后重试，runner 不会自动挑选记录'
                           .format(replay_id, path, limit),
            }
            return
        allowed, reason = outbox_replay_precondition(target)
        if not allowed:
            self.outbox_replay_probe = {
                'id': replay_id, 'attempted': False, 'success': False,
                'preStatus': target.get('status'),
                'preResponseStatus': target.get('responseStatus'),
                'blocker': reason,
            }
            return

        replay_path = path.rstrip('/') + '/' + str(replay_id) + '/replay'
        print('  [A7] POST {0}（显式重放 429/5xx 失败记录）'.format(replay_path))
        try:
            replay_record, replay_parsed = self.recorder.observe(
                'outbox.replay', 'POST', replay_path)
        except TransportError as error:
            self.outbox_replay_probe = {
                'id': replay_id, 'attempted': True, 'success': False,
                'preStatus': target.get('status'),
                'preResponseStatus': target.get('responseStatus'),
                'blocker': '重放连接层失败：' + str(error),
            }
            return

        replay_data = replay_parsed.get('data')
        replay_errors = replay_result_errors(replay_data)
        success = (replay_record['httpStatus'] == 200
                   and replay_parsed.get('resultCode') == SERVICE_SUCCESS_CODE
                   and not replay_errors
                   and replay_data.get('success') is True
                   and replay_data.get('outcome') == 'SUCCEEDED'
                   and replay_data.get('status') in OUTBOX_SUCCESS_TERMINAL_STATUSES)
        post_status = None
        post_valid = False
        post_error = None
        if success:
            try:
                _, refreshed = self.recorder.observe(
                    'outbox.list(after-replay)', 'GET', path, query={'limit': limit})
                refreshed_items = refreshed.get('data') if isinstance(refreshed.get('data'), list) else []
                post_item = find_outbox_item(refreshed_items, replay_id)
                if post_item is None:
                    post_error = '重放后列表未包含 id={0}（提高 --outbox-limit 或检查店铺权限）'.format(replay_id)
                else:
                    post_status = post_item.get('status')
                    post_valid = post_status in OUTBOX_SUCCESS_TERMINAL_STATUSES
                    if not post_valid:
                        post_error = '重放后 status={0}，不是成功终态'.format(post_status)
            except TransportError as error:
                post_error = '重放后回读连接层失败：' + str(error)

        self.outbox_replay_probe = {
            'id': replay_id,
            'attempted': True,
            'success': bool(success and post_valid),
            'httpStatus': replay_record['httpStatus'],
            'resultCode': replay_parsed.get('resultCode'),
            'outcome': replay_data.get('outcome') if isinstance(replay_data, dict) else None,
            'resultStatus': replay_data.get('status') if isinstance(replay_data, dict) else None,
            'preStatus': target.get('status'),
            'preResponseStatus': target.get('responseStatus'),
            'postStatus': post_status,
            'postVerified': post_valid,
            'contractErrors': replay_errors,
            'blocker': post_error,
        }

    def run_crosscheck(self):
        """A4：第二个店铺各跑一次 orders（token 缓存键 = clientId:sha256(refreshToken)，不得串用）。"""
        if self.options.second_shop_id is None:
            return
        shop = self.options.second_shop_id
        print('  [A4] orders 交叉验证（shopId={0}）'.format(shop))
        try:
            record, parsed = self.recorder.observe(
                'orders.syncOrders(crosscheck)', 'POST', '/spapi/sync/orders',
                query={'shopId': shop}, platform_endpoint='GET /orders/v0/orders')
        except TransportError as error:
            self.operations['orders-crosscheck'] = {
                'ok': False, 'records': [], 'blockers': ['连接层失败：' + str(error)],
                'platformCallDocumented': True}
            return
        count = parsed.get('data') if isinstance(parsed.get('data'), int) else None
        record['response']['values'] = {'ordersSynced': count, 'shopId': shop}
        self.operations['orders-crosscheck'] = {
            'ok': record['ok'], 'records': [record['name']],
            'blockers': [] if record['ok'] else ['第二店铺 syncOrders 未成功'],
            'platformCallDocumented': True}

    # ------------------------------------------------------------- 制品绑定
    def load_attestation_blocks(self):
        """载入 operator 证据（A3 前两例 / A4 token 摘要 / A8 限流回填）与制品绑定信息。"""
        target_att = attestation_section(self.attestation, 'target') or {}
        identity_att = attestation_section(self.attestation, 'identity') or {}
        config_path = self.options.config_file
        config_sha = None
        if config_path and os.path.isfile(config_path):
            config_sha = sha256_file(config_path)
        image_digest = target_att.get('imageDigest')
        binding = None
        if image_digest:
            binding = 'imageDigest'
        elif config_sha:
            binding = 'configFile.sha256'
        self.target = {
            'serviceUrl': self.options.service_url,
            'profile': self.self_description.get('profile'),
            'imageDigest': image_digest,
            'configFile': {'path': config_path, 'sha256': config_sha},
            'binding': binding,
            'selfDescription': self.self_description,
            'operatorAttestation': bool(self.attestation),
        }
        self.identity = {
            'appId': identity_att.get('appId'),
            'sellerIdMasked': mask_seller_id(identity_att.get('sellerId')),
            'marketplaceId': self.options.marketplace_id,
            'region': identity_att.get('region'),
            'source': 'attestation' if self.attestation else None,
        }
        observations = attestation_section(self.attestation, 'a8', 'rateLimitObservations')
        if isinstance(observations, list):
            for item in observations:
                if not isinstance(item, dict):
                    continue
                window = item.get('localWindowAfter') or {}
                self.rate_limit.append({
                    'endpoint': item.get('endpoint'),
                    'header': item.get('header'),
                    'localWindowAfter': {'maxRequests': window.get('maxRequests'),
                                         'windowMs': window.get('windowMs')},
                    'evidence': item.get('evidence'),
                    'source': 'attestation',
                })

    def selected_operations(self):
        """§3.3 的 operations 段 = 启用 operation 的调用记录（不含错误码探针与 burst 噪声）。"""
        index = {}
        for record in self.recorder.operations:
            index.setdefault(record['name'], []).append(record)
        selected = []
        for outcome in self.operations.values():
            for name in outcome.get('records') or []:
                bucket = index.get(name) or []
                if bucket:
                    selected.append(bucket.pop(0))
        return selected
    # ------------------------------------------------------------------ 判定
    @staticmethod
    def _criterion(key, level, evidence=None, blockers=None, notes=None):
        out = {'pass': LEVEL_RANK[level] >= CRITERIA_REQUIRED[key], 'evidenceLevel': level}
        if evidence:
            out['evidence'] = evidence
        if blockers:
            out['blockers'] = blockers
        if notes:
            out['notes'] = notes
        return out

    def _platform_ops_ok(self):
        return [name for name, outcome in self.operations.items()
                if outcome.get('ok') and outcome.get('platformCallDocumented')]

    def _matched_error_codes(self):
        return sorted({entry['expected'] for entry in self.error_codes if entry.get('matched')})

    def criteria(self):
        return {
            'A1': self._a1(), 'A2': self._a2(), 'A3': self._a3(), 'A4': self._a4(),
            'A5': self._a5(), 'A6': self._a6(), 'A7': self._a7(), 'A8': self._a8(),
        }

    def _a1(self):
        """认证/分页/幂等/错误分类：平台 operation 有成功样例 + 四类错误码齐。"""
        ops_ok = self._platform_ops_ok()
        matched = self._matched_error_codes()
        missing = [code for code in EXPECTED_ERROR_CODES if code not in matched]
        evidence = []
        blockers = []
        if ops_ok:
            evidence.append('operations:' + ','.join(ops_ok))
        else:
            blockers.append('无任何平台 operation 成功样例')
        if missing:
            blockers.append('缺错误码样例：' + ','.join(str(code) for code in missing))
        else:
            evidence.append('errorCodes:401,403,404,429')
        level = 'E4' if (ops_ok and not missing) else ('E1' if ops_ok else 'E0')
        notes = []
        without_code = [str(entry['expected']) for entry in self.error_codes
                        if entry.get('matched') and not entry.get('platformCode')]
        if without_code:
            notes.append('以下样例匹配到 platformStatus 但未解析出 platformCode：'
                         + ','.join(without_code) + '（诊断文本截断或平台未回 code）')
        return self._criterion('A1', level, evidence=evidence, blockers=blockers, notes=notes)

    def _a2(self):
        """缺凭证显式失败：不存在 shopId 的两条探针都必须返回 no credential for shopId=…"""
        explicit = [check for check in self.checks if check.get('pass')]
        blockers = []
        evidence = []
        if len(explicit) >= 2:
            evidence.append('absentShopChecks:' + ','.join(check['check'] for check in explicit))
        else:
            blockers.append('缺凭证调用未显式失败（{0}/{1} 条通过；期望两条都返回 no credential for shopId=…，'
                            '而不是空列表/0）'.format(len(explicit), len(self.checks) or 2))
        if self.self_description.get('startupRequireCredentials') is not True:
            blockers.append('spapi.startup.require-credentials 非 true（自检未强制凭证）')
        else:
            evidence.append('selfDescription.startupRequireCredentials')
        level = 'E4' if not blockers else ('E1' if self.checks else 'E0')
        return self._criterion('A2', level, evidence=evidence, blockers=blockers)

    def _a3(self):
        """启动自检：两例「拒绝启动」只能来自 operator 证据，第三例 runner 自己观测。"""
        refusals = attestation_section(self.attestation, 'a3', 'startupRefusals')
        cases = {}
        if isinstance(refusals, list):
            for item in refusals:
                if isinstance(item, dict) and item.get('case'):
                    cases[str(item['case'])] = item
        required_cases = ('prod-without-credentials', 'mock-profile')
        missing = [case for case in required_cases if not (cases.get(case) or {}).get('refused')]
        evidence = []
        blockers = []
        if missing:
            blockers.append('缺「拒绝启动」证据（--attest a3.startupRefusals，需含 {0} 且 refused=true）'
                            .format('/'.join(required_cases)))
        else:
            evidence.append('attestation:a3.startupRefusals')
        observed = [check for check in self.checks if check.get('pass')]
        if observed:
            evidence.append('absentShopChecks')
        else:
            blockers.append('未观测到「不存在 shopId 显式失败」（§4.1 第 3 步）')
        if self.self_description.get('startupCheckRan') is True:
            evidence.append('selfDescription.startupCheckRan')
        else:
            blockers.append('被测进程未回报 startupCheckRan=true（启动自检未执行）')
        if self.target.get('binding'):
            evidence.append('target.binding=' + str(self.target['binding']))
        else:
            blockers.append('未记录镜像摘要或配置哈希（记录无法绑定到具体制品）')
        level = 'E4' if not blockers else ('E1' if evidence else 'E0')
        return self._criterion('A3', level, evidence=evidence, blockers=blockers)

    def _a4(self):
        """两店 token 差异：摘要来自 operator 证据，交叉调用由 runner 观测。"""
        observations = attestation_section(self.attestation, 'a4', 'tokenObservations')
        digests = set()
        shops = set()
        if isinstance(observations, list):
            for item in observations:
                if isinstance(item, dict) and item.get('shopId') is not None:
                    shops.add(str(item['shopId']))
                    digest = item.get('accessTokenSha256')
                    if digest:
                        digests.add(str(digest))
        evidence = []
        blockers = []
        if len(shops) >= 2 and len(digests) >= 2:
            evidence.append('attestation:a4.tokenObservations')
        else:
            blockers.append('缺两店 token 摘要证据（--attest a4.tokenObservations：两条记录、摘要必须不同）')
        if self.options.second_shop_id is None:
            blockers.append('未提供 --second-shop-id，无法交叉验证两店各跑一次 orders')
        elif (self.operations.get('orders-crosscheck') or {}).get('ok'):
            evidence.append('operations:orders.syncOrders(crosscheck)')
        else:
            blockers.append('第二店铺 orders 交叉验证未成功（shopId={0}）'
                            .format(self.options.second_shop_id))
        if (self.operations.get('orders') or {}).get('ok'):
            evidence.append('operations:orders.syncOrders')
        else:
            blockers.append('主店铺 orders 未成功（A4 的两次调用缺一）')
        level = 'E4' if not blockers else ('E1' if evidence else 'E0')
        return self._criterion('A4', level, evidence=evidence, blockers=blockers)

    def _a5(self):
        """联调记录（唯一取证路径）：每个启用的 operation 都要有真实成功样例。"""
        enabled = list(self.options.operations)
        missing_ops = [name for name in enabled if not (self.operations.get(name) or {}).get('ok')]
        matched = self._matched_error_codes()
        missing_codes = [code for code in EXPECTED_ERROR_CODES if code not in matched]
        evidence = []
        blockers = []
        if missing_ops:
            blockers.append('缺 operation 成功样例：' + ','.join(missing_ops))
        else:
            evidence.append('operations:' + ','.join(enabled))
        if missing_codes:
            blockers.append('缺错误码样例：' + ','.join(str(code) for code in missing_codes))
        else:
            evidence.append('errorCodes:401,403,404,429')
        if 'reports-download' in enabled:
            chain = self.document_chain or {}
            if chain.get('sha256') and chain.get('decompressedBytes'):
                evidence.append('documentChain:sha256=' + str(chain['sha256'])[:16] + '…')
            else:
                blockers.append('文档链路未闭环（缺解压后字节数 / 内容哈希）')
        if self.target.get('binding'):
            evidence.append('target.binding=' + str(self.target['binding']))
        else:
            blockers.append('未记录镜像摘要或配置哈希（记录无法绑定到具体制品）')
        if self.self_description.get('mockClientsActive'):
            blockers.append('被测进程 mockClientsActive=true（C1 违反，样例数据是假证据）')
        if not blockers:
            level = 'E4'
        elif self._platform_ops_ok():
            level = 'E1'
        else:
            level = 'E0'
        if self.stub_self_declared and LEVEL_RANK[level] > LEVEL_RANK['E2']:
            level = 'E2'
            blockers.append('被测进程自声明 stub=true（桩夹具，不是真实部署）→ A5 封顶 E2，'
                            '本记录不构成联调证据')
        return self._criterion('A5', level, evidence=evidence, blockers=blockers)

    def _a6(self):
        """能力清单端点与实现一致（Task 6，requiredLevel=E3）。"""
        probe = self.connectors_probe or {}
        entry = probe.get('connectorEntry')
        evidence = []
        blockers = []
        level = 'E0'
        if not probe:
            blockers.append('未探测能力清单端点')
        elif not isinstance(entry, dict):
            blockers.append('GET {0} 未返回 spapi 条目（HTTP {1}，resultCode={2}）— 能力清单端点不可用或契约不符'
                            .format(probe.get('path'), probe.get('httpStatus'),
                                    probe.get('resultCode')))
        elif not entry.get('evidenceLevel'):
            blockers.append('能力清单 spapi 条目缺 evidenceLevel（无法与 ConnectorEvidencePolicy 对照）')
        else:
            level = 'E3'
            evidence.append('connectors:' + json.dumps(
                {key: entry.get(key) for key in ('connector', 'enabled', 'evidenceLevel')},
                ensure_ascii=False, sort_keys=True))
        return self._criterion('A6', level, evidence=evidence, blockers=blockers)

    def _a7(self):
        """A7：只读安全视图最高 E3；只有显式重放并回读成功才可到 E4。"""
        probe = self.outbox_probe or {}
        replay = self.outbox_replay_probe or {}
        evidence = []
        blockers = []
        level = 'E0'
        if not probe:
            blockers.append('未探测 Outbox 列表端点，无法核验失败记录安全视图与重放前置')
        elif probe.get('observed'):
            blockers.append('Outbox 列表端点不可达：' + str(probe.get('observed')))
        elif probe.get('contractErrors'):
            blockers.append('Outbox 安全视图契约不符：' + '; '.join(probe.get('contractErrors') or []))
        else:
            level = 'E3'
            statuses = ','.join(str(item) for item in (probe.get('statuses') or []))
            evidence.append('outbox:list(itemCount={0},statuses={1})'.format(
                probe.get('itemCount'), statuses or 'none'))
            if replay.get('success'):
                level = 'E4'
                evidence.append('outbox:replay(id={0},pre={1}/{2},post={3},outcome={4})'.format(
                    replay.get('id'), replay.get('preStatus'), replay.get('preResponseStatus'),
                    replay.get('postStatus'), replay.get('outcome')))
            elif replay:
                blockers.append('Outbox 显式重放未成功：' + str(
                    replay.get('blocker') or replay.get('contractErrors') or '未满足重放契约'))
            else:
                blockers.append('缺真实 429/5xx 的显式重放证据：需提供 --outbox-replay-id，'
                                '原记录须为 FAILED/DLQ 且 responseStatus=429/5xx，重放后须回读成功；'
                                'A7 requiredLevel=E4')
        if self.stub_self_declared and LEVEL_RANK[level] > LEVEL_RANK['E2']:
            level = 'E2'
            blockers.append('被测进程自声明 stub=true（桩夹具，不是真实部署）→ A7 封顶 E2，'
                            '本记录不构成真实重放证据')
        return self._criterion('A7', level, evidence=evidence, blockers=blockers)

    def _a8(self):
        """限流与配额真实：回填窗口只能来自 operator 证据，429 样例由 runner 观测。"""
        matched_429 = any(entry.get('matched') and entry.get('expected') == 429
                          for entry in self.error_codes)
        evidence = []
        blockers = []
        if self.rate_limit:
            evidence.append('rateLimit:' + ','.join(
                str(item.get('endpoint')) for item in self.rate_limit))
        else:
            blockers.append('缺 x-amzn-RateLimit-Limit 回填观测（--attest a8.rateLimitObservations；'
                            'P0-52b 未实现，服务侧无结构化出口）')
        if matched_429:
            evidence.append('errorCodes:429')
        else:
            blockers.append('缺 429 样例（沙箱 burst=15 时 5 rps 稳态不耗尽令牌桶，需并发负载或专用店铺）')
        if not blockers:
            level = 'E4'
        elif self.rate_limit:
            level = 'E1'
        else:
            level = 'E0'
        return self._criterion('A8', level, evidence=evidence, blockers=blockers)
    # ---------------------------------------------------------------- operations
    def op_orders(self):
        shop = self.options.shop_id
        record, parsed = self.recorder.observe(
            'orders.syncOrders', 'POST', '/spapi/sync/orders', query={'shopId': shop},
            platform_endpoint='GET /orders/v0/orders')
        count = parsed.get('data') if isinstance(parsed.get('data'), int) else None
        record['response']['values'] = {'ordersSynced': count}
        blockers = []
        if not record['ok']:
            blockers.append('syncOrders 未成功（resultCode={0}）'.format(parsed.get('resultCode')))
        elif count is None:
            blockers.append('syncOrders 成功但未返回条数（口径应为整数）')
        return {'ok': record['ok'], 'records': [record['name']], 'blockers': blockers,
                'platformCallDocumented': True}

    def op_inventory(self):
        shop = self.options.shop_id
        record, parsed = self.recorder.observe(
            'inventory.sync', 'POST', '/spapi/inventory/sync/' + quote_path(shop),
            platform_endpoint='GET /fba/inventory/v1/summaries')
        count = parsed.get('data') if isinstance(parsed.get('data'), int) else None
        record['response']['values'] = {'skusSynced': count}
        blockers = []
        if not record['ok']:
            blockers.append('inventory sync 未成功（resultCode={0}）'.format(parsed.get('resultCode')))
        elif count is None:
            blockers.append('inventory sync 成功但未返回落库 SKU 数')
        return {'ok': record['ok'], 'records': [record['name']], 'blockers': blockers,
                'platformCallDocumented': True}

    def op_feeds(self):
        shop = self.options.shop_id
        body = {'shopId': shop, 'marketplaceId': self.options.marketplace_id,
                'content': DEFAULT_FEED_CONTENT}
        submit, parsed = self.recorder.observe(
            'feeds.submit', 'POST', '/spapi/feeds/submit', body=body,
            platform_endpoint='POST /feeds/2021-06-30/documents + POST /feeds/2021-06-30/feeds')
        feed_id = parsed.get('data') if isinstance(parsed.get('data'), str) else None
        submit['response']['values'] = {'feedId': feed_id}
        records = [submit['name']]
        blockers = []
        if not submit['ok']:
            blockers.append('feeds.submit 未成功（resultCode={0}）'.format(parsed.get('resultCode')))
        if not feed_id:
            blockers.append('feeds.submit 未返回 feedId')
            return {'ok': False, 'records': records, 'blockers': blockers,
                    'platformCallDocumented': True}

        status_record, status_parsed = self.recorder.observe(
            'feeds.status', 'GET',
            '/spapi/feeds/status/' + quote_path(shop) + '/' + quote_path(feed_id),
            platform_endpoint='GET /feeds/2021-06-30/feeds/{feedId}')
        records.append(status_record['name'])
        data = status_parsed.get('data')
        values = {}
        if isinstance(data, dict):
            values = {key: data.get(key) for key in
                      ('feedId', 'processingStatus', 'resultFeedDocumentId')}
        status_record['response']['values'] = values
        self.feed = values
        if not status_record['ok']:
            blockers.append('feeds.status 未成功（resultCode={0}）'.format(status_parsed.get('resultCode')))
        elif not values.get('processingStatus'):
            blockers.append('feeds.status 未返回 processingStatus（P0-30 的 resultFeedDocumentId 亦需复核）')
        return {'ok': submit['ok'] and status_record['ok'] and not blockers,
                'records': records, 'blockers': blockers, 'platformCallDocumented': True}

    def op_reports(self):
        shop = self.options.shop_id
        request, parsed = self.recorder.observe(
            'reports.request', 'POST', '/spapi/finance/report/request',
            query={'shopId': shop, 'marketplaceId': self.options.marketplace_id,
                   'reportType': self.options.report_type},
            platform_endpoint='POST /reports/2021-06-30/reports')
        report_id = parsed.get('data') if isinstance(parsed.get('data'), str) else None
        request['response']['values'] = {'reportId': report_id}
        records = [request['name']]
        blockers = []
        if not request['ok']:
            blockers.append('reports.request 未成功（resultCode={0}）'.format(parsed.get('resultCode')))
        if not report_id:
            blockers.append('reports.request 未返回 reportId')
            return {'ok': False, 'records': records, 'blockers': blockers,
                    'platformCallDocumented': True}

        latest = {}
        for attempt in range(1, max(1, self.options.report_poll_attempts) + 1):
            status_record, status_parsed = self.recorder.observe(
                'reports.status', 'GET', '/spapi/finance/report/' + quote_path(report_id),
                query={'shopId': shop},
                platform_endpoint='GET /reports/2021-06-30/reports/{reportId}')
            records.append(status_record['name'])
            data = status_parsed.get('data')
            latest = {}
            if isinstance(data, dict):
                latest = {key: data.get(key) for key in
                          ('reportId', 'reportType', 'processingStatus', 'documentId')}
            status_record['response']['values'] = latest
            if not status_record['ok']:
                blockers.append('reports.status 第 {0} 次即失败（resultCode={1}）'.format(
                    attempt, status_parsed.get('resultCode')))
                break
            processing = latest.get('processingStatus')
            if processing in TERMINAL_REPORT_STATUS:
                break
            if attempt < self.options.report_poll_attempts:
                time.sleep(self.options.report_poll_interval)
        self.report = latest
        processing = latest.get('processingStatus')
        if processing != 'DONE':
            blockers.append('reports 未达 DONE（processingStatus={0}，轮询 {1} 次）'.format(
                processing, self.options.report_poll_attempts))
        elif not latest.get('documentId'):
            blockers.append('reports 已 DONE 但 documentId 为空（P0-27 的闭环点）')
        return {'ok': not blockers, 'records': records, 'blockers': blockers,
                'platformCallDocumented': True}

    def op_reports_download(self):
        document_id = (self.report or {}).get('documentId')
        record_name = []
        if not document_id:
            self.document_chain = {
                'reportId': (self.report or {}).get('reportId'),
                'reportDocumentId': None,
                'downloadStatus': None,
                'resultCode': None,
                'compressionAlgorithm': None,
                'decompressedBytes': None,
                'sha256': None,
                'blockers': ['未取得 documentId（reports 未到 DONE 或未启用 reports）'],
            }
            return {'ok': False, 'records': record_name, 'blockers': self.document_chain['blockers'],
                    'platformCallDocumented': True}
        record, parsed = self.recorder.observe(
            'reports.downloadDocument', 'GET', '/spapi/finance/document/' + quote_path(document_id),
            query={'shopId': self.options.shop_id},
            platform_endpoint='GET /reports/2021-06-30/documents/{reportDocumentId} + S3 预签名下载')
        record_name.append(record['name'])
        content = parsed.get('data') if isinstance(parsed.get('data'), str) else None
        blockers = []
        if not record['ok']:
            blockers.append('document 下载未成功（resultCode={0}）'.format(parsed.get('resultCode')))
        if content is None:
            blockers.append('document 未返回文本内容 → 无法计算解压后字节数与哈希')
            self.document_chain = {
                'reportId': (self.report or {}).get('reportId'),
                'reportDocumentId': document_id,
                'downloadStatus': record['httpStatus'],
                'resultCode': parsed.get('resultCode'),
                'compressionAlgorithm': None,
                'decompressedBytes': None,
                'sha256': None,
                'blockers': blockers,
            }
            return {'ok': False, 'records': record_name, 'blockers': blockers,
                    'platformCallDocumented': True}
        payload = content.encode('utf-8')
        line_count = len([line for line in content.splitlines() if line.strip()])
        record['response']['values'] = {'sha256': sha256_bytes(payload),
                                        'decompressedBytes': len(payload),
                                        'lineCount': line_count}
        self.document_chain = {
            'reportId': (self.report or {}).get('reportId'),
            'reportDocumentId': document_id,
            'downloadStatus': record['httpStatus'],
            'resultCode': parsed.get('resultCode'),
            # 解压由服务端完成（ReportsRealClient），算法对本 runner 不可见 → 如实记 null，
            # 不以「假设是 GZIP」充数。
            'compressionAlgorithm': None,
            'decompressedBytes': len(payload),
            'lineCount': line_count,
            'sha256': sha256_bytes(payload),
            'contentStored': False,
            'blockers': blockers,
        }
        return {'ok': record['ok'] and not blockers, 'records': record_name,
                'blockers': blockers, 'platformCallDocumented': True}

    def op_finances(self):
        record, parsed = self.recorder.observe(
            'finances.listEvents', 'GET', '/spapi/finance/events',
            query={'shopId': self.options.shop_id},
            platform_endpoint='GET /finances/v0/financialEvents')
        data = parsed.get('data')
        values = {}
        if isinstance(data, list):
            histogram = {}
            for item in data:
                if not isinstance(item, dict):
                    continue
                key = str(item.get('type') or 'unknown')
                histogram[key] = histogram.get(key, 0) + 1
            values = {'eventTypeHistogram': histogram, 'eventCount': len(data)}
        record['response']['values'] = values
        blockers = []
        if not record['ok']:
            blockers.append('finances.listEvents 未成功（resultCode={0}）'.format(parsed.get('resultCode')))
        elif not isinstance(data, list):
            blockers.append('finances.listEvents 未返回数组（四类事件口径无法核对）')
        return {'ok': record['ok'] and not blockers, 'records': [record['name']],
                'blockers': blockers, 'platformCallDocumented': True}

    def op_fees(self):
        query = {
            'shopId': self.options.shop_id,
            'marketplaceId': self.options.marketplace_id,
            'idType': self.options.fees_id_type,
            'asin': self.options.fees_id_value,
            'price': self.options.fees_price,
            'currency': self.options.fees_currency,
        }
        record, parsed = self.recorder.observe(
            'fees.estimate', 'POST', '/spapi/finance/fees/estimate', query=query,
            platform_endpoint='POST /products/fees/v0/feesEstimate')
        data = parsed.get('data')
        values = {}
        if isinstance(data, dict):
            values = {key: data.get(key) for key in
                      ('asin', 'sku', 'currency', 'referralFee', 'fulfillmentFee', 'totalFees')}
        record['response']['values'] = values
        blockers = []
        if not record['ok']:
            blockers.append('fees.estimate 未成功（resultCode={0}）'.format(parsed.get('resultCode')))
        elif values.get('totalFees') is None:
            blockers.append('fees.estimate 未返回 totalFees（佣金/配送费/杂项口径无法核对）')
        return {'ok': record['ok'] and not blockers, 'records': [record['name']],
                'blockers': blockers, 'platformCallDocumented': True}

# ======================================================================================
# 前置校验 / 结论 / 产出
# ======================================================================================

#: 被测服务名（与 `ConnectorSelfDescription.SERVICE` 一致，漂移即前置失败）。
SERVICE_NAME = 'amz-service-spapi'

#: C1 要求的 profile。
REQUIRED_PROFILE = 'prod'

#: 记录中**必须**保留的 `target.profileChecks` 条目（顺序即执行顺序）。
PROFILE_CHECK_NAMES = ('service', 'connector', 'profile', 'mockClientsActive', 'startupCheckRan',
                       'startupRequireCredentials', 'loadedCredentialCount')


def read_token(path):
    """从文件读 JWT（`token` 头）。值只在内存里，永不打印、永不写入记录。"""
    if not path:
        return None
    try:
        with open(path, 'r', encoding='utf-8') as handle:
            token = handle.read().strip()
    except OSError as error:
        raise PreconditionError('--auth-token-file 读取失败：{0}'.format(redact(str(error))))
    if not token:
        raise PreconditionError('--auth-token-file 内容为空')
    return token


def preflight_checks(description, connector, allow_stub=False):
    """C1 硬约束（runbook §0）：任一不满足即退出码 2 —— 整份记录作废，**不产出报告**。

    额外护栏：`tools/connector-acceptance/fake-service.py` 的自描述恒带 `stub=true`。
    默认**拒绝**（否则桩夹具会被当成联调证据）；显式 `--allow-stub` 才继续，且 A5 封顶 E2，
    记录永远到不了「已接通（联调中）」。
    """
    checks = []

    def note(name, passed, observed):
        checks.append({'check': name, 'pass': bool(passed), 'observed': observed})
        return bool(passed)

    if not note('service', description.get('service') == SERVICE_NAME,
                {'observed': description.get('service'), 'expected': SERVICE_NAME}):
        raise PreconditionError('被测服务不是 {0}（service={1}）：拒绝产出记录'
                                .format(SERVICE_NAME, description.get('service')))
    if not note('connector', description.get('connector') == connector,
                {'observed': description.get('connector'), 'expected': connector}):
        raise PreconditionError('被测服务连接器不是 {0}（connector={1}）'
                                .format(connector, description.get('connector')))
    profiles = [item.strip() for item in str(description.get('profile') or '').split(',')
                if item.strip()]
    if not note('profile', REQUIRED_PROFILE in profiles, {'profile': description.get('profile')}):
        raise PreconditionError(
            'C1 违反：被测进程 profile={0} 不含 {1}。mock profile 下 Reports/Finances/Fees '
            '返回离线样例数据，据此产出的「成功样例」是假证据，整份记录作废'
            .format(description.get('profile'), REQUIRED_PROFILE))
    if not note('mockClientsActive', description.get('mockClientsActive') is False,
                {'mockClientsActive': description.get('mockClientsActive')}):
        raise PreconditionError('C1 违反：mockClientsActive={0}（mock 客户端返回离线样例数据）'
                                .format(description.get('mockClientsActive')))
    if not note('startupCheckRan', description.get('startupCheckRan') is True,
                {'startupCheckRan': description.get('startupCheckRan')}):
        raise PreconditionError('无法核验 C1：startupCheckRan=false（进程未执行启动自检，'
                                '无法证明「不是偷偷用 mock」）')
    if not note('startupRequireCredentials', description.get('startupRequireCredentials') is True,
                {'startupRequireCredentials': description.get('startupRequireCredentials')}):
        raise PreconditionError('C1 不成立：spapi.startup.require-credentials 非 true'
                                '（启动自检未强制凭证，缺凭证可能表现为「正常返回」）')
    count = description.get('loadedCredentialCount')
    if not note('loadedCredentialCount', isinstance(count, int) and count >= 1,
                {'loadedCredentialCount': count}):
        raise PreconditionError('C1 违反：loadedCredentialCount={0}（无已加载店铺凭证）'.format(count))
    stub = description.get('stub') is True
    if stub and not allow_stub:
        raise PreconditionError(
            '被测服务自描述 stub=true（本地桩夹具，不是真实部署）：默认拒绝，避免桩回放被当成联调证据。'
            '确需跑 runner 机械自检请加 --allow-stub（记录必被降级：A5 封顶 E2）')
    note('stubSelfDeclared', not stub, {'stub': stub, 'allowed': bool(allow_stub)})
    return checks


def normalize_operations(value):
    """`--operations` 解析：逗号分隔 → 元组（空串归空元组）。"""
    return tuple(part.strip() for part in str(value or '').split(',') if part.strip())


def validate_options(options):
    """参数自检。**结构性问题先报**（否则「缺 --marketplace-id」会盖住非法 connector 之类的真错）。"""
    if not options.service_url:
        raise PreconditionError('缺少 --service-url')
    parts = urllib.parse.urlsplit(options.service_url)
    if parts.scheme not in ('http', 'https') or not parts.netloc:
        raise PreconditionError('--service-url 必须是 http(s)://host[:port]（实际 {0}）'
                                .format(options.service_url))
    if options.connector != 'spapi':
        raise PreconditionError('本 runner 目前只支持 --connector spapi（实际 {0}）'.format(options.connector))
    if not options.operations:
        raise PreconditionError('--operations 不能为空（合法值：{0}）'.format(','.join(ALL_OPERATIONS)))
    unknown = [name for name in options.operations if name not in ALL_OPERATIONS]
    if unknown:
        raise PreconditionError('未知 operation：{0}（合法值：{1}）'
                                .format(','.join(unknown), ','.join(ALL_OPERATIONS)))
    if options.shop_id is None:
        raise PreconditionError('缺少 --shop-id')
    needs_marketplace = [name for name in options.operations if name in OPERATIONS_REQUIRING_MARKETPLACE]
    if needs_marketplace and not options.marketplace_id:
        raise PreconditionError('缺少 --marketplace-id（{0} 需要 marketplaceId）'
                                .format(','.join(needs_marketplace)))
    if options.report_poll_interval < MIN_INTERVAL_S:
        raise PreconditionError('--report-poll-interval 必须 ≥ {0}（沙箱 5 rps 的串行约束）'
                                .format(MIN_INTERVAL_S))
    if options.report_poll_attempts < 1:
        raise PreconditionError('--report-poll-attempts 必须 ≥ 1')
    if options.burst_max < 1:
        raise PreconditionError('--burst-max 必须 ≥ 1')
    if options.timeout <= 0:
        raise PreconditionError('--timeout 必须 > 0')
    if not isinstance(options.outbox_path, str) or not options.outbox_path.startswith('/'):
        raise PreconditionError('--outbox-path 必须以 / 开头')
    if '?' in options.outbox_path:
        raise PreconditionError('--outbox-path 不得包含查询串')
    if options.outbox_limit < 1:
        raise PreconditionError('--outbox-limit 必须 ≥ 1')
    if options.outbox_replay_id is not None and options.outbox_replay_id <= 0:
        raise PreconditionError('--outbox-replay-id 若提供必须 > 0')


def build_conclusion(criteria):
    """整体判定与三种对外文案之一（与 `ConnectorEvidencePolicy.Assessment` 同口径）。"""
    blockers = []
    weakest = 'E5'
    for key in CRITERION_KEYS:
        item = criteria.get(key) or {}
        level = item.get('evidenceLevel') or 'E0'
        if LEVEL_RANK[level] < LEVEL_RANK[weakest]:
            weakest = level
        if not item.get('pass'):
            blockers.append('{0}({1}<E{2})'.format(key, level, CRITERIA_REQUIRED[key]))
    reachable = LEVEL_RANK[criteria['A5']['evidenceLevel']] >= LEVEL_RANK['E4']
    api_ready = not blockers
    if api_ready:
        display = DISPLAY_READY
    elif reachable:
        display = DISPLAY_REACHABLE
    else:
        display = DISPLAY_CAPABLE
    return {'apiReady': api_ready, 'reachable': reachable, 'evidenceLevel': weakest,
            'displayText': display, 'blockers': blockers}


def generator_options(options):
    """`generator.options`：记录本次运行的可审计参数（**不含** JWT 与任何密钥）。"""
    out = {
        'serviceUrl': options.service_url,
        'connector': options.connector,
        'shopId': options.shop_id,
        'secondShopId': options.second_shop_id,
        'marketplaceId': options.marketplace_id,
        'operations': list(options.operations),
        'outDir': options.out_dir,
        'configFile': options.config_file,
        'reportType': options.report_type,
        'reportPollAttempts': options.report_poll_attempts,
        'reportPollIntervalSeconds': options.report_poll_interval,
        'absentShopId': options.absent_shop_id,
        'errorShop401': options.error_shop_401,
        'errorShop403': options.error_shop_403,
        'errorShop429': options.error_shop_429,
        'missingReportId': options.missing_report_id,
        'missingFeedId': options.missing_feed_id,
        'connectorsPath': options.connectors_path,
        'outboxPath': options.outbox_path,
        'outboxLimit': options.outbox_limit,
        'outboxReplayId': options.outbox_replay_id,
        'burstMax': options.burst_max,
        'authTokenFileProvided': bool(options.auth_token_file),
        'minIntervalMs': int(MIN_INTERVAL_S * 1000),
    }
    if options.attest and os.path.isfile(options.attest):
        out['attestationFile'] = {'path': options.attest, 'sha256': sha256_file(options.attest)}
    else:
        out['attestationFile'] = None
    return out


def assemble_report(options, run, criteria, conclusion):
    generated_at = _dt.datetime.now().astimezone().replace(microsecond=0).isoformat()
    head = dict(conclusion)
    head['notes'] = list(run.notes)
    head['evidenceBoundary'] = [
        'runner 只与被测服务通话，不接触任何平台凭证，也不直接调用 Amazon；',
        'operations 记录的是 runner→被测服务的进出口（observer=runner->amz-service-spapi），'
        '不是出站流量；出站真实性由平台错误码样例与（需 operator 证据的）限流头回填间接支撑；',
        'errorCodes.* 的平台字段来自被测服务的诊断文本（P0-52a 的结构化错误契约仍未实现），'
        '属文本解析的间接证据；',
        'A7 只读列表契约成立时最高 E3；E4 仅限非桩服务、显式 --outbox-replay-id、'
        '原记录 FAILED/DLQ 且 responseStatus=429/5xx、重放返回成功并回读成功；'
        'runner 不自动扫描或自动重放；A6 在端点不可达或契约不符时同样不通过。',
    ]
    return {
        'schemaVersion': SCHEMA_VERSION,
        'connector': options.connector,
        'generatedAt': generated_at,
        'generator': {
            'path': RUNNER_REL_PATH,
            'sha256': runner_sha256(),
            'options': generator_options(options),
            'operationsPlan': [
                {'operation': name, 'triggeredEndpoint': trigger, 'platformEndpoint': platform}
                for name, trigger, platform in OPERATION_PLAN
            ],
            'criteriaRequired': dict(CRITERIA_REQUIRED),
        },
        'target': run.target,
        'identity': run.identity,
        'operations': run.selected_operations(),
        'errorCodes': run.error_codes,
        'rateLimit': run.rate_limit,
        'documentChain': run.document_chain,
        'criteria': criteria,
        'conclusion': head,
    }


def runner_sha256():
    return sha256_file(os.path.abspath(__file__))


def write_report(out_dir, connector, report):
    """原子写 JSON + 同名 `.sha256`（`<hex>  <filename>`，两个空格）。"""
    os.makedirs(out_dir, exist_ok=True)
    stamp = _dt.datetime.now().strftime('%Y%m%d%H%M%S')
    filename = 'connector-acceptance-{0}-{1}.json'.format(connector, stamp)
    path = os.path.join(out_dir, filename)
    payload = (json.dumps(report, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    digest = sha256_bytes(payload)
    tmp_path = path + '.tmp'
    with open(tmp_path, 'wb') as handle:
        handle.write(payload)
    os.replace(tmp_path, path)
    with open(path + '.sha256', 'w', encoding='utf-8', newline='\n') as handle:
        handle.write('{0}  {1}\n'.format(digest, filename))
    return path, digest


def print_report_summary(report, path, digest):
    conclusion = report['conclusion']
    print('')
    print('判定（requiredLevel 见 generator.criteriaRequired）：')
    for key in CRITERION_KEYS:
        item = report['criteria'][key]
        flag = 'PASS' if item['pass'] else 'FAIL'
        detail = 'evidence=' + ','.join(item.get('evidence') or []) if item['pass'] \
            else 'blockers=' + '; '.join(item.get('blockers') or [])
        print('  {0} {1} {2:<3} {3}'.format(flag, key, item['evidenceLevel'], detail))
    print('结论：apiReady={0} reachable={1} displayText={2}'.format(
        conclusion['apiReady'], conclusion['reachable'], conclusion['displayText']))
    if conclusion['blockers']:
        print('阻断项：' + ', '.join(conclusion['blockers']))
    print('机密扫描：{0}{1}'.format(
        conclusion.get('secretScan', {}).get('verdict'),
        ('（已掩码：' + ','.join(conclusion.get('secretScan', {}).get('hits') or []) + '）')
        if conclusion.get('secretScan', {}).get('hits') else ''))
    print('产出：{0}'.format(path))
    print('   sha256：{0}'.format(digest))


def plan_text(options):
    lines = [
        'AmazonERP 连接器验收 runner —— dry-run（不连接任何服务，不产出验收记录）',
        '  退出码：dry-run / --selftest 属 runner 自检，0=自检通过、1=自检失败；',
        '          真实验收运行才是 runbook §3.2 的 0=全部达标 / 1=有缺项 / 2=前置不满足。',
        '',
        '前置（任一不满足 → 真实验收直接退出码 2，整份记录作废）：',
        '  - C1：profile 含 prod（读 GET /spapi/status 的自描述，进程外可核验）',
        '  - C1：mockClientsActive=false、startupCheckRan=true、startupRequireCredentials=true',
        '  - C1：loadedCredentialCount ≥ 1',
        '  - C2：机密一律不落盘（sellerId 打码；token / 密钥 / 预签名 URL 不进记录）',
        '  - C3：每个启用的 operation 至少一次成功 + 401/403/404/429 四类错误码',
        '',
        '调用计划（串行，两次真实调用之间 ≥{0}ms）：'.format(int(MIN_INTERVAL_S * 1000)),
    ]
    for name, trigger, platform in OPERATION_PLAN:
        mark = '启用' if name in options.operations else '跳过'
        lines.append('  [{0}] {1:<16} {2}'.format(mark, name, trigger))
        lines.append('        └ 对应平台端点（文档映射，runner 不可观测）：{0}'.format(platform))
    lines.append('')
    lines.append('结论判据（requiredLevel）：')
    lines.append('  ' + '  '.join('{0}=E{1}'.format(key, CRITERIA_REQUIRED[key]) for key in CRITERION_KEYS))
    lines.append('')
    try:
        validate_options(options)
        lines.append('参数自检：(无缺项，可直接执行真实验收)')
    except PreconditionError as error:
        lines.append('参数自检：{0}'.format(error))
    lines.append('提醒：A7 只读列表最高 E3，E4 需显式 --outbox-replay-id 并完成真实重放与回读；'
                 'A6 能力清单端点已实现，需按实际可达路径探测 → 即使其余全绿，'
                 'displayText 最高只能是「{0}」。'.format(DISPLAY_REACHABLE))
    return '\n'.join(lines)

def run_acceptance(options):
    """真实运行：前置 → operations → 错误码 → 自检 → 判定 → 脱敏 → 落盘。返回退出码。"""
    token = read_token(options.auth_token_file)
    attestation = load_attestation(options.attest)
    client = ServiceClient(options.service_url, token=token, timeout_s=options.timeout)
    recorder = Recorder(client, echo=not options.quiet)

    print('[1/6] 前置：GET /spapi/status（核验 C1：prod + 非 mock + 自检已跑 + 凭证已加载）')
    try:
        status, text, _ = recorder.raw_call('GET', '/spapi/status')
    except TransportError as error:
        raise PreconditionError('被测服务不可达：{0}'.format(error))
    if status in (401, 403):
        raise PreconditionError('GET /spapi/status 返回 HTTP {0}：/spapi/** 受 BaseAuthInterceptor 保护，'
                                '需要 --auth-token-file 提供有效 JWT（token 头）'.format(status))
    if status != 200:
        raise PreconditionError('GET /spapi/status 返回 HTTP {0}（期望 200）'.format(status))
    parsed = parse_result(text)
    description = parsed.get('data')
    if parsed.get('resultCode') != SERVICE_SUCCESS_CODE or not isinstance(description, dict):
        raise PreconditionError('GET /spapi/status 未返回自描述对象（resultCode={0}）：{1}'
                                .format(parsed.get('resultCode'), parsed.get('message')))
    profile_checks = preflight_checks(description, options.connector,
                                      allow_stub=bool(options.allow_stub))

    run = AcceptanceRun(recorder, options, attestation, description)
    run.load_attestation_blocks()
    run.target['profileChecks'] = profile_checks
    if run.stub_self_declared:
        run.notes.append('被测进程自描述 stub=true（--allow-stub）：本记录只证 runner 机械正确性，'
                         '属桩回放级证据，**不构成**联调证据')

    print('[2/6] operations：{0}'.format(','.join(options.operations)))
    run.run_operations()
    run.run_crosscheck()

    print('[3/6] 错误码覆盖（401/403/404/429）')
    run.collect_error_codes()

    print('[4/6] 运行期自检（缺凭证必须显式失败）+ 能力清单端点 + Outbox 安全视图')
    run.check_absent_shop()
    run.probe_connectors()
    run.probe_outbox()

    print('[5/6] 判定 A1–A8')
    criteria = run.criteria()
    conclusion = build_conclusion(criteria)

    print('[6/6] 渲染后机密扫描 + 原子写盘')
    report = assemble_report(options, run, criteria, conclusion)
    hits = []
    masked = mark_untrusted(report, hits)
    scan = {'verdict': 'FAIL' if hits else 'PASS', 'hits': sorted(set(hits)),
            'scannedAfterRender': True}
    masked['conclusion']['secretScan'] = scan
    if hits:
        masked['conclusion']['notes'] = list(masked['conclusion'].get('notes') or []) + [
            '渲染后二次扫描命中机密特征（{0}），已掩码；该记录判为不合格'
            .format(','.join(sorted(set(hits))))]
        masked['conclusion']['apiReady'] = False
        masked['conclusion']['blockers'] = list(masked['conclusion']['blockers']) + [
            'secretScan:' + ','.join(sorted(set(hits)))]
        masked['conclusion']['displayText'] = (DISPLAY_REACHABLE
                                               if masked['conclusion']['reachable'] else DISPLAY_CAPABLE)

    exit_code = EXIT_PASS if (masked['conclusion']['apiReady'] and scan['verdict'] == 'PASS') else EXIT_GAPS
    path, digest = write_report(options.out_dir, options.connector, masked)
    print_report_summary(masked, path, digest)
    print('退出码：{0}（0=全部达标 / 1=已执行但有缺项 / 2=前置不满足）'.format(exit_code))
    return exit_code


def run_selftest():
    """不连服务的自检：验证 runner 自身的解析 / 脱敏 / 判定 / 写盘逻辑。"""
    import tempfile

    failures = []

    def check(name, condition, detail=''):
        if condition:
            print('  PASS  {0}'.format(name))
        else:
            failures.append(name)
            print('  FAIL  {0}  {1}'.format(name, detail))

    print('[selftest] 解析')
    ok = parse_result('{"code":200,"message":"操作成功","data":3}')
    check('parse_result.success', ok['resultCode'] == 200 and ok['data'] == 3 and ok['raw'] is False)
    bad = parse_result('{not json')
    check('parse_result.raw-text', bad['raw'] is True and bad['resultCode'] is None)
    diagnostic = ('sync failed: fetchOrders failed shopId=1001 status=404 '
                  'body={"errors":[{"code":"NotFound","message":"no such report"}]}')
    detail = parse_platform_error(diagnostic)
    check('parse_platform_error.status', detail['status'] == 404, str(detail))
    check('parse_platform_error.code', detail['code'] == 'NotFound', str(detail))
    check('parse_platform_error.message', detail['message'] == 'no such report', str(detail))
    check('parse_platform_error.empty', parse_platform_error('')['status'] is None)

    print('[selftest] 脱敏')
    presigned = ('download failed status=403 path=/reports/2021-06-30/documents/r-1'
                 '?x-amz-signature=abc123&x-amz-credential=AKIAEXAMPLE&x-amz-security-token=t0ken')
    cleaned = redact(presigned)
    check('redact.presigned-values', 'abc123' not in cleaned and 'AKIAEXAMPLE' not in cleaned
          and 't0ken' not in cleaned, cleaned)
    check('redact.keeps-diagnostics', 'status=403' in cleaned and 'documents/r-1' in cleaned, cleaned)
    hits = []
    mark_untrusted(cleaned, hits)
    check('mark_untrusted.no-false-positive', not hits, ','.join(hits))
    hits = []
    mark_untrusted('Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghij', hits)
    check('mark_untrusted.jwt', 'jwt' in hits, ','.join(hits))
    hits = []
    mark_untrusted('leaked AKIAABCDEFGHIJKLMNOP here', hits)
    check('mark_untrusted.aws-key', 'aws_access_key_id' in hits, ','.join(hits))
    check('mask_seller_id', mask_seller_id('A1B2C3D4E5') == 'A1B2****'
          and mask_seller_id('AB') == '****' and mask_seller_id(None) is None)

    print('[selftest] 参数校验（真实验收执行的就是这套规则）')

    def parse_args(argv):
        parsed = build_parser().parse_args(argv)
        parsed.operations = normalize_operations(parsed.operations)
        return parsed

    def rejects(argv, needle):
        try:
            validate_options(parse_args(argv))
        except PreconditionError as error:
            return needle in str(error)
        return False

    no_marketplace = ['--service-url', 'http://127.0.0.1:8096', '--shop-id', '1001']
    base = no_marketplace + ['--marketplace-id', 'ATVPDKIKX0DER']
    check('validate.accepts-minimal-finances',
          validate_options(parse_args(no_marketplace + ['--operations', 'finances,fees'])) is None)
    check('validate.rejects-bad-url',
          rejects(['--service-url', 'not-a-url', '--shop-id', '1'], 'http(s)://'))
    check('validate.rejects-bad-connector',
          rejects(base + ['--connector', 'bogus'], '只支持 --connector spapi'))
    check('validate.rejects-unknown-operation',
          rejects(base + ['--operations', 'bogus,orders'], '未知 operation'))
    check('validate.requires-marketplace-for-orders',
          rejects(no_marketplace + ['--operations', 'orders'], 'marketplaceId'))
    check('validate.rejects-fast-poll',
          rejects(base + ['--operations', 'reports', '--report-poll-interval', '0.05'],
                  '--report-poll-interval'))
    check('validate.rejects-bad-outbox-path',
          rejects(base + ['--outbox-path', 'spapi/connectors/outbox'], '--outbox-path'))
    check('validate.rejects-outbox-path-query',
          rejects(base + ['--outbox-path', '/spapi/connectors/outbox?limit=1'], '查询串'))
    check('validate.rejects-zero-outbox-limit',
          rejects(base + ['--outbox-limit', '0'], '--outbox-limit'))
    check('validate.rejects-nonpositive-outbox-replay-id',
          rejects(base + ['--outbox-replay-id', '0'], '--outbox-replay-id'))

    print('[selftest] 键集合冻结（与 runbook §3.3 对照）')
    frozen = ('schemaVersion', 'connector', 'generatedAt', 'generator', 'target', 'identity',
              'operations', 'errorCodes', 'rateLimit', 'documentChain', 'criteria', 'conclusion')
    check('report.top-level-keys-frozen', REPORT_TOP_LEVEL_KEYS == frozen,
          ','.join(REPORT_TOP_LEVEL_KEYS))
    check('criteria.keys', tuple(CRITERIA_REQUIRED.keys()) == CRITERION_KEYS,
          ','.join(CRITERIA_REQUIRED.keys()))
    check('criteria.required-levels',
          CRITERIA_REQUIRED == {'A1': 4, 'A2': 3, 'A3': 4, 'A4': 4,
                                'A5': 4, 'A6': 3, 'A7': 4, 'A8': 4},
          str(CRITERIA_REQUIRED))
    check('display-text.three-values',
          {DISPLAY_READY, DISPLAY_REACHABLE, DISPLAY_CAPABLE}
          == {'API-Ready（已联调）', '已接通（联调中）', '具备对接能力（未联调）'})

    print('[selftest] 结构与隐私')
    shape = shape_of({'token': 'secret-value', 'reportId': 'r-1', 'items': [1, 2]})
    check('shape_of.no-string-content', 'secret-value' not in json.dumps(shape, ensure_ascii=False),
          json.dumps(shape, ensure_ascii=False))
    check('shape_of.keeps-reportId', shape.get('reportId') == 'r-1', str(shape))
    array_shape = shape_of([1, 2])
    check('shape_of.array',
          array_shape['itemCount'] == 2 and array_shape['itemShape']['type'] == 'number',
          str(array_shape))
    check('shape_of.depth0-values-whitelist-only',
          set(shape.keys()) == {'type', 'keys', 'reportId'}, str(sorted(shape.keys())))
    nested = shape_of({'items': ['secret-in-array'], 'note': 'secret-in-object'})
    check('shape_of.nested-no-content',
          'secret-in-array' not in json.dumps(nested, ensure_ascii=False)
          and 'secret-in-object' not in json.dumps(nested, ensure_ascii=False),
          json.dumps(nested, ensure_ascii=False))
    check('query_string.none', query_string(None) is None and query_string({}) is None)
    check('query_string.encodes', query_string({'shopId': 1001}) == 'shopId=1001',
          str(query_string({'shopId': 1001})))
    client = ServiceClient('http://127.0.0.1:1', token='jwt-value')
    check('observed_headers.token-masked', observed_headers(client).get('token') == REDACTED,
          json.dumps(observed_headers(client), ensure_ascii=False))

    print('[selftest] Outbox/A7 契约')
    outbox_valid = {
        'id': 7001, 'shopId': 1001, 'operationId': 'orders.getOrders',
        'httpMethod': 'GET', 'requestPath': '/orders/v0/orders', 'status': 'FAILED',
        'attemptCount': 1, 'maxAttempts': 4, 'responseStatus': 429,
        'marketplaceId': 'ATVPDKIKX0DER', 'responseRequestId': 'req-1',
        'lastErrorCode': 'HTTP_429', 'lastErrorMessage': 'throttled',
        'createdAt': '2026-09-25T12:00:00', 'updatedAt': '2026-09-25T12:00:01',
        'completedAt': None, 'expectedStatuses': [200], 'rateLimitVariant': None,
    }
    check('outbox.valid-view', not outbox_view_errors(outbox_valid),
          str(outbox_view_errors(outbox_valid)))
    missing = dict(outbox_valid)
    missing.pop('completedAt')
    check('outbox.requires-nullable-fields',
          any('缺字段' in item for item in outbox_view_errors(missing)))
    forbidden = dict(outbox_valid)
    forbidden['requestBody'] = 'must-not-leak'
    check('outbox.rejects-forbidden-body',
          any('禁止外泄字段' in item for item in outbox_view_errors(forbidden)))
    bad_status = dict(outbox_valid)
    bad_status['status'] = 'DONE'
    check('outbox.rejects-bad-status',
          any('status 非法' in item for item in outbox_view_errors(bad_status)))
    bad_id = dict(outbox_valid)
    bad_id['id'] = True
    check('outbox.rejects-boolean-id',
          any('id 必须是正整数' in item for item in outbox_view_errors(bad_id)))
    query_path = dict(outbox_valid)
    query_path['requestPath'] = '/orders/v0/orders?limit=1'
    check('outbox.rejects-query-in-request-path',
          any('不得包含查询串' in item for item in outbox_view_errors(query_path)))
    allowed, reason = outbox_replay_precondition(outbox_valid)
    check('outbox.replay-precondition-429-failed', allowed and reason is None, str(reason))
    succeeded = dict(outbox_valid)
    succeeded['status'] = 'SUCCEEDED'
    succeeded['responseStatus'] = 200
    allowed, reason = outbox_replay_precondition(succeeded)
    check('outbox.rejects-successful-record', not allowed and '不是 FAILED/DLQ' in reason, str(reason))
    replay_ok = {'success': True, 'outcome': 'SUCCEEDED', 'status': 'REPLAYED', 'message': None}
    check('outbox.replay-result-valid', not replay_result_errors(replay_ok),
          str(replay_result_errors(replay_ok)))
    replay_bad = dict(replay_ok)
    replay_bad['success'] = 'true'
    check('outbox.replay-result-rejects-string-bool',
          any('success 必须是布尔值' in item for item in replay_result_errors(replay_bad)))

    def a7_criterion(probe, replay=None, stub=False):
        run = object.__new__(AcceptanceRun)
        run.outbox_probe = probe
        run.outbox_replay_probe = replay or {}
        run.stub_self_declared = stub
        return run._a7()

    list_probe = {'itemCount': 1, 'statuses': ['FAILED'], 'contractErrors': []}
    replay_probe = {'id': 7001, 'success': True, 'preStatus': 'FAILED',
                    'preResponseStatus': 429, 'postStatus': 'REPLAYED', 'outcome': 'SUCCEEDED'}
    item = a7_criterion(list_probe)
    check('a7.list-contract-e3', item['evidenceLevel'] == 'E3' and not item['pass'], str(item))
    item = a7_criterion(list_probe, replay_probe)
    check('a7.replay-success-e4', item['evidenceLevel'] == 'E4' and item['pass'], str(item))
    item = a7_criterion(list_probe, replay_probe, stub=True)
    check('a7.stub-capped-e2', item['evidenceLevel'] == 'E2' and not item['pass'], str(item))

    print('[selftest] 判定')
    all_pass = {key: {'pass': True, 'evidenceLevel': 'E4'} for key in CRITERION_KEYS}
    check('conclusion.api-ready', build_conclusion(all_pass)['displayText'] == DISPLAY_READY)
    partial = {}
    for key in CRITERION_KEYS:
        level = 'E4' if key in ('A1', 'A2', 'A3', 'A4', 'A5', 'A8') else 'E0'
        partial[key] = {'pass': LEVEL_RANK[level] >= CRITERIA_REQUIRED[key],
                        'evidenceLevel': level}
    reachable = build_conclusion(partial)
    check('conclusion.reachable-only',
          (not reachable['apiReady']) and reachable['reachable']
          and reachable['displayText'] == DISPLAY_REACHABLE, str(reachable))
    empty = build_conclusion({key: {'pass': False, 'evidenceLevel': 'E0'} for key in CRITERION_KEYS})
    check('conclusion.capable-only',
          empty['displayText'] == DISPLAY_CAPABLE and not empty['reachable']
          and len(empty['blockers']) == 8, str(empty))
    check('conclusion.blocker-format', 'A1(E1<E4)' in build_conclusion(
        {key: {'pass': False, 'evidenceLevel': 'E1'} for key in CRITERION_KEYS})['blockers'])

    print('[selftest] 落盘')
    with tempfile.TemporaryDirectory() as tmp:
        report_path, digest = write_report(tmp, 'selftest',
                                           {'schemaVersion': SCHEMA_VERSION, 'note': '中文内容'})
        with open(report_path, 'rb') as handle:
            payload = handle.read()
        check('write_report.sha256-matches', sha256_bytes(payload) == digest)
        with open(report_path + '.sha256', 'r', encoding='utf-8') as handle:
            line = handle.read().strip()
        check('write_report.sha256-file-format',
              line == digest + '  ' + os.path.basename(report_path), line)
        check('write_report.atomic', not os.path.exists(report_path + '.tmp'))
        check('write_report.filename', os.path.basename(report_path).startswith(
            'connector-acceptance-selftest-') and report_path.endswith('.json'))

    print('')
    if failures:
        print('[selftest] FAILED：{0} 项未通过（{1}）'.format(len(failures), ','.join(failures)))
        return EXIT_GAPS
    print('[selftest] PASSED（未连接任何服务；真实验收仍需按 runbook §3 执行）')
    return EXIT_PASS


def build_parser():
    parser = argparse.ArgumentParser(
        prog='acceptance_runner.py',
        description='AmazonERP 连接器验收 runner（runbook §3；退出码 0=全部达标 / 1=有缺项 / 2=前置不满足）')
    parser.add_argument('--service-url', help='被测服务地址，如 http://127.0.0.1:8096')
    parser.add_argument('--connector', default='spapi', help='连接器标识（目前只支持 spapi）')
    parser.add_argument('--shop-id', type=int, help='主店铺 ID（必须已加载凭证）')
    parser.add_argument('--second-shop-id', type=int, help='第二店铺 ID（A4 交叉验证）')
    parser.add_argument('--marketplace-id', help='marketplaceId，如 ATVPDKIKX0DER')
    parser.add_argument('--operations', default=','.join(ALL_OPERATIONS),
                        help='逗号分隔的 operation 白名单（默认全部）')
    parser.add_argument('--out-dir', default='./acceptance-out', help='验收记录输出目录')
    parser.add_argument('--auth-token-file', help='JWT 文件（token 头；只读，不落盘）')
    parser.add_argument('--attest', help='operator 已脱敏证据文件（A3/A4/A8 与制品绑定）')
    parser.add_argument('--config-file', default=DEFAULT_CONFIG_FILE,
                        help='被测配置文件的仓库相对路径（用于记录 sha256 绑定）')
    parser.add_argument('--report-type', default=DEFAULT_REPORT_TYPE)
    parser.add_argument('--report-poll-attempts', type=int, default=20)
    parser.add_argument('--report-poll-interval', type=float, default=15.0,
                        help='报表状态轮询间隔（秒，必须 ≥{0}）'.format(MIN_INTERVAL_S))
    parser.add_argument('--absent-shop-id', type=int, default=DEFAULT_ABSENT_SHOP_ID,
                        help='用于「缺凭证显式失败」探针的不存在店铺 ID')
    parser.add_argument('--error-shop-401', type=int, help='失效 refresh token 的店铺（构造平台 401）')
    parser.add_argument('--error-shop-403', type=int, help='缺 scope 的店铺（构造平台 403）')
    parser.add_argument('--error-shop-429', type=int, help='用于超速尝试的店铺（构造平台 429）')
    parser.add_argument('--missing-report-id', default='r-not-exists')
    parser.add_argument('--missing-feed-id', default='f-not-exists')
    parser.add_argument('--connectors-path', default='/spapi/connectors',
                        help='能力清单端点路径；直连服务用 /spapi/connectors，经网关可用 /api/connectors')
    parser.add_argument('--outbox-path', default='/spapi/connectors/outbox',
                        help='Outbox 安全视图端点路径；默认直连服务路径')
    parser.add_argument('--outbox-limit', type=int, default=20,
                        help='Outbox 列表查询条数（必须 ≥ 1）')
    parser.add_argument('--outbox-replay-id', type=int,
                        help='显式指定要重放的 Outbox id；未提供时只读探测，不自动挑选记录')
    parser.add_argument('--fees-id-type', default='ASIN')
    parser.add_argument('--fees-id-value', default='B000000000', help='对应端点参数 asin')
    parser.add_argument('--fees-price', default='19.99')
    parser.add_argument('--fees-currency', default='USD')
    parser.add_argument('--timeout', type=float, default=30.0, help='单次 HTTP 超时（秒）')
    parser.add_argument('--burst-max', type=int, default=30, help='429 尝试的最大连续调用次数')
    parser.add_argument('--dry-run', action='store_true', help='只打印调用计划，不连服务、不产出记录')
    parser.add_argument('--selftest', action='store_true', help='runner 自身自检（不连服务）')
    parser.add_argument('--allow-stub', action='store_true',
                        help='允许对自声明 stub=true 的桩服务跑机械自检（记录必被降级：A5 封顶 E2）')
    parser.add_argument('--quiet', action='store_true', help='不逐条打印 HTTP 调用')
    return parser


def main(argv=None):
    parser = build_parser()
    options = parser.parse_args(argv)
    options.operations = normalize_operations(options.operations)
    if options.selftest:
        return run_selftest()
    if options.dry_run:
        print(plan_text(options))
        return EXIT_PASS
    try:
        validate_options(options)
        return run_acceptance(options)
    except PreconditionError as error:
        print('前置不满足（退出码 {0}）：{1}'.format(EXIT_PRECONDITION, error), file=sys.stderr)
        return EXIT_PRECONDITION
    except TransportError as error:
        print('连接层失败（退出码 {0}）：{1}'.format(EXIT_PRECONDITION, error), file=sys.stderr)
        return EXIT_PRECONDITION


if __name__ == '__main__':
    sys.exit(main())
