#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/connector-acceptance/fake-service.py —— 本地「被测服务」替身（P0-52c 的自检装置）。

作用
----
在没有 API 凭证、也没有运行真实 amz-service-spapi 进程时，验证 acceptance_runner.py 的
**机械正确性**：请求路径/方法/参数/请求头、`Result` 解析、平台错误文本解析、脱敏、判定、
原子落盘、退出码。

它不是证据（硬约束）
--------------------
1. 自描述**恒带** `"stub": true`；acceptance_runner 默认**拒绝**（退出码 2，且不产出记录），
   只有显式 `--allow-stub` 才继续，且 A5 封顶 E2 → displayText 永远到不了「已接通（联调中）」。
2. 本进程不调用 Amazon、不接触任何平台凭证；返回值全部是**写死的夹具**。
3. 夹具里的 `AKIA…`、预签名参数、会话 token 都是**伪造的诱饵**，专门用来验证脱敏链路。

用法
----
    python tools/connector-acceptance/fake-service.py --port 0
    # stdout 首行：STUB_READY <实际端口>
    python tools/connector-acceptance/fake-service.py --port 8096 --connectors-ok

端点（与 amz-service-spapi 的对外契约一致；`Result` = `{code, message, data}`）
------------------------------------------------------------------------
    GET  /spapi/status                             自描述（stub=true）
    POST /spapi/sync/orders?shopId=                成功：data=条数；异常店铺：平台错误诊断文本
    POST /spapi/inventory/sync/{shopId}            成功：data=SKU 数；不存在店铺：no credential
    POST /spapi/feeds/submit                       成功：data=feedId
    GET  /spapi/feeds/status/{shopId}/{feedId}     成功：data={feedId,processingStatus,…}
    POST /spapi/finance/report/request             成功：data=reportId
    GET  /spapi/finance/report/{reportId}          成功：DONE + documentId；不存在：平台 404 诊断
    GET  /spapi/finance/document/{documentId}      成功：data=TSV 文本
    GET  /spapi/finance/events?shopId=             成功：data=四类事件数组
    POST /spapi/finance/fees/estimate              成功：data=佣金/配送费/合计
    GET  /api/connectors                           默认 404（Task 6 未实现）；--connectors-ok 才返回条目
    GET  /__stub/requests / __stub/health          自检内省（不需要 token 头）
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STUB_PORT_ENV = 'STUB_READY'

#: 各店铺的错误类别：值 = 平台 HTTP 状态码。其余店铺按成功处理。
ERROR_SHOPS = {401001: 401, 403001: 403, 429001: 429}

#: 平台错误体（镜像 Amazon 的 errors[].code/message 形态）。
PLATFORM_ERRORS = {
    401: ('Unauthorized', 'Access token is invalid or expired'),
    403: ('AccessDenied', 'The authorization scope does not include the requested operation'),
    404: ('NotFound', 'Requested resource does not exist'),
    429: ('QuotaExceeded', 'Request is throttled'),
}

#: 伪造的预签名诱饵（验证 runner 必须把它掩成 ***，且不得产生误报）。
DECOY_PRESIGNED = ('https://s3.amazonaws.com/amz-stub-documents/r-not-exists'
                   '?x-amz-signature=deadbeefdeadbeefdeadbeef'
                   '&x-amz-credential=AKIASTUBSTUBSTUB0000/20260924/us-east-1/s3/aws4_request'
                   '&x-amz-security-token=stub-session-token')

SETTLEMENT_TSV = '\n'.join([
    'settlement-id\tsettlement-start-date\tsettlement-end-date\tdeposit-date\ttotal-amount\tcurrency',
    '1001-20260901\t2026-09-01T00:00:00Z\t2026-09-15T23:59:59Z\t2026-09-18T00:00:00Z\t1234.56\tUSD',
    '1001-20260916\t2026-09-16T00:00:00Z\t2026-09-23T23:59:59Z\t2026-09-25T00:00:00Z\t987.65\tUSD',
])

FINANCIAL_EVENTS = [
    {'type': 'INCOME', 'amount': '120.00', 'currency': 'USD', 'postedDate': '2026-09-20T01:02:03Z'},
    {'type': 'REFUND', 'amount': '-35.50', 'currency': 'USD', 'postedDate': '2026-09-20T04:05:06Z'},
    {'type': 'FEE', 'amount': '-18.25', 'currency': 'USD', 'postedDate': '2026-09-21T07:08:09Z'},
    {'type': 'ADJUSTMENT', 'amount': '4.75', 'currency': 'USD', 'postedDate': '2026-09-22T10:11:12Z'},
]


def platform_diagnostic(operation, shop_id, status, detail=None):
    """构造 OrdersClient 风格的诊断文本：`<op> failed shopId=<id> status=<code> body={...}`。"""
    code, message = PLATFORM_ERRORS[status]
    error = {'code': code, 'message': message}
    if detail:
        error.update(detail)
    body = json.dumps({'errors': [error]}, separators=(',', ':'), ensure_ascii=False)
    return '{0} failed shopId={1} status={2} body={3}'.format(operation, shop_id, status, body)


class StubHandler(BaseHTTPRequestHandler):
    server_version = 'AmazonERPStub/1.0'
    protocol_version = 'HTTP/1.1'

    # ---------------------------------------------------------------- 基础
    def log_message(self, fmt, *args):
        if self.server.verbose:
            sys.stderr.write('[stub] ' + (fmt % args) + '\n')

    def _send(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json;charset=UTF-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    @staticmethod
    def _ok(data, message='操作成功'):
        return {'code': 200, 'message': message, 'data': data}

    @staticmethod
    def _fail(message, data=None):
        """`Result.failure` = HTTP 200 + code 400（与 amz-common 的 Result 一致）。"""
        return {'code': 400, 'message': message, 'data': data}

    def do_GET(self):
        self._handle('GET')

    def do_POST(self):
        self._handle('POST')

    def _handle(self, method):
        parts = urllib.parse.urlsplit(self.path)
        query = {key: value[0] for key, value in urllib.parse.parse_qs(parts.query).items()}
        length = int(self.headers.get('Content-Length') or 0)
        raw = self.rfile.read(length) if length > 0 else b''
        body = None
        if raw:
            try:
                body = json.loads(raw.decode('utf-8'))
            except ValueError:
                body = raw.decode('utf-8', 'replace')
        self.server.requests_log.append({
            'method': method, 'path': parts.path, 'query': query,
            'token': self.headers.get('token'),
            'accept': self.headers.get('Accept'),
            'contentType': self.headers.get('Content-Type'),
            'body': body if isinstance(body, dict) else None,
        })
        status, payload = self._dispatch(method, parts.path, query, body)
        self._send(status, payload)

    # ---------------------------------------------------------------- 路由
    def _dispatch(self, method, path, query, body):
        if path.startswith('/__stub/'):
            return self._stub_route(method, path)
        if path.startswith(('/spapi/', '/api/')) and not (self.headers.get('token') or '').strip():
            # 与 BaseAuthInterceptor 一致：/spapi/** 需要 token 头（用户 JWT）。
            return 401, {'code': 401, 'message': 'missing token header', 'data': None}

        if method == 'GET' and path == '/spapi/status':
            return 200, self._ok(self.server.self_description())
        if method == 'POST' and path == '/spapi/sync/orders':
            return self._orders(query)
        match = re.match(r'^/spapi/inventory/sync/([^/]+)$', path)
        if method == 'POST' and match:
            return self._inventory(urllib.parse.unquote(match.group(1)))
        if method == 'POST' and path == '/spapi/feeds/submit':
            return self._feeds_submit(body)
        match = re.match(r'^/spapi/feeds/status/([^/]+)/([^/]+)$', path)
        if method == 'GET' and match:
            return 200, self._ok({
                'feedId': urllib.parse.unquote(match.group(2)),
                'processingStatus': 'DONE',
                'resultFeedDocumentId': 'fd-{0}-0001'.format(urllib.parse.unquote(match.group(2))),
            })
        if method == 'POST' and path == '/spapi/finance/report/request':
            shop = query.get('shopId') or '0'
            return 200, self._ok('r-{0}-0001'.format(shop))
        match = re.match(r'^/spapi/finance/report/([^/]+)$', path)
        if method == 'GET' and match:
            return self._report(urllib.parse.unquote(match.group(1)), query)
        match = re.match(r'^/spapi/finance/document/([^/]+)$', path)
        if method == 'GET' and match:
            return 200, self._ok(SETTLEMENT_TSV)
        if method == 'GET' and path == '/spapi/finance/events':
            return 200, self._ok(FINANCIAL_EVENTS)
        if method == 'POST' and path == '/spapi/finance/fees/estimate':
            return 200, self._ok({
                'asin': query.get('asin') or 'B000000000',
                'sku': 'STUB-SKU-0001',
                'currency': query.get('currency') or 'USD',
                'referralFee': 0.3,
                'fulfillmentFee': 3.14,
                'totalFees': 3.44,
            })
        if method == 'GET' and path == '/api/connectors':
            if self.server.connectors_ok:
                return 200, self._ok({'connectors': [
                    {'connector': 'spapi', 'enabled': True, 'evidenceLevel': 'E4'},
                ]})
            return 404, {'code': 404, 'message': 'Not Found', 'data': None}
        return 404, {'code': 404, 'message': 'Not Found', 'data': None}

    def _stub_route(self, method, path):
        if method == 'GET' and path == '/__stub/health':
            return 200, {'ok': True, 'stub': True}
        if method == 'GET' and path == '/__stub/requests':
            return 200, {'requests': list(self.server.requests_log)}
        return 404, {'code': 404, 'message': 'Not Found', 'data': None}

    def _orders(self, query):
        shop = query.get('shopId') or ''
        status = ERROR_SHOPS.get(int(shop)) if shop.isdigit() else None
        if status:
            self.server.error_counts[status] = self.server.error_counts.get(status, 0) + 1
            return 200, self._fail(platform_diagnostic('fetchOrders', shop, status))
        if shop == str(self.server.absent_shop_id):
            return 200, self._fail(
                'syncOrders failed shopId={0}: no credential for shopId={0}'.format(shop))
        return 200, self._ok(self.server.orders_synced)

    def _inventory(self, shop):
        if shop == str(self.server.absent_shop_id):
            return 200, self._fail(
                'inventory sync failed shopId={0}: no credential for shopId={0}'.format(shop))
        status = ERROR_SHOPS.get(int(shop)) if shop.isdigit() else None
        if status:
            return 200, self._fail(platform_diagnostic('getInventorySummaries', shop, status))
        return 200, self._ok(self.server.skus_synced)

    def _feeds_submit(self, body):
        shop = (body or {}).get('shopId') or '0'
        return 200, self._ok('f-{0}-0001'.format(shop))

    def _report(self, report_id, query):
        missing = (report_id == self.server.missing_report_id)
        if missing:
            return 200, self._fail(platform_diagnostic(
                'getReport', query.get('shopId') or '0', 404,
                detail={'path': '/reports/2021-09-01/documents/r-not-exists',
                        'documentUrl': DECOY_PRESIGNED}))
        if self.server.report_status_calls < self.server.report_pending_polls:
            self.server.report_status_calls += 1
            return 200, self._ok({'reportId': report_id,
                                  'reportType': 'GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE',
                                  'processingStatus': 'IN_PROGRESS', 'documentId': None})
        return 200, self._ok({
            'reportId': report_id,
            'reportType': 'GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE',
            'processingStatus': 'DONE',
            'documentId': 'd-{0}'.format(report_id),
        })


def build_server(args):
    server = ThreadingHTTPServer((args.host, args.port), StubHandler)
    server.daemon_threads = True
    server.verbose = args.verbose
    server.orders_synced = args.orders_synced
    server.skus_synced = args.skus_synced
    server.absent_shop_id = args.absent_shop_id
    server.missing_report_id = args.missing_report_id
    server.connectors_ok = args.connectors_ok
    server.report_pending_polls = args.report_pending_polls
    server.report_status_calls = 0
    server.requests_log = []
    server.error_counts = {}
    server.profile = args.profile
    server.credentials = args.credentials

    def self_description():
        return {
            'service': 'amz-service-spapi',
            'connector': 'spapi',
            'profile': server.profile,
            'mockClientsActive': server.profile != 'prod',
            'startupCheckRan': True,
            'startupRequireCredentials': True,
            'loadedCredentialCount': server.credentials,
            'stub': True,
        }

    server.self_description = self_description
    return server


def build_parser():
    parser = argparse.ArgumentParser(description='AmazonERP 连接器验收用的本地桩服务（非证据）')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=0, help='0 = 由内核分配并在 stdout 打印')
    parser.add_argument('--profile', default='prod', help='自描述里的 profile（mock 用于验证 C1 拒绝）')
    parser.add_argument('--credentials', type=int, default=2, help='自描述里的 loadedCredentialCount')
    parser.add_argument('--orders-synced', type=int, default=3)
    parser.add_argument('--skus-synced', type=int, default=7)
    parser.add_argument('--absent-shop-id', type=int, default=999999999)
    parser.add_argument('--missing-report-id', default='r-not-exists')
    parser.add_argument('--report-pending-polls', type=int, default=0,
                        help='前 N 次报表状态返回 IN_PROGRESS（验证轮询循环）')
    parser.add_argument('--connectors-ok', action='store_true',
                        help='让 GET /api/connectors 返回 spapi 条目（默认 404=Task 6 未实现）')
    parser.add_argument('--verbose', action='store_true')
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    server = build_server(args)
    print('{0} {1}'.format(STUB_PORT_ENV, server.server_address[1]), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == '__main__':
    sys.exit(main())