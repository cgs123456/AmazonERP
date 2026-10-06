# -*- coding: utf-8 -*-
"""Deterministic synthetic dataset generator for AmazonERP (spec section 7).

Contract
--------
* Column lists are **never** hand-copied: they come from
  `tools/synthetic-data/schema/schema-snapshot.json`, which is derived from the
  repository DDL and gated in CI by `snapshot_schema.py --check`.
* Identical ``(dataset_id, seed)`` => byte-identical SQL/JSONL/manifest.  There is
  no wall clock anywhere in the artefacts.
* Every artefact is marked SYNTHETIC: SQL/JSONL headers, ``data_origin`` /
  ``source`` / ``data_source`` columns where they exist, reserved id bands, and
  obviously fake PII (``example.invalid`` mail, 555 numbers, SYNTHETIC names).
* Columns that are not present in *all* DDL definitions of a table
  (``conditional_columns``) and columns that only exist in MySQL-8-incompatible
  ``ALTER TABLE ... ADD COLUMN IF NOT EXISTS`` statements are **not** written
  unless ``--include-conditional`` is passed (P0-39 evidence, snapshot section 4).
* Unique keys declared in the DDL are enforced; a deterministic repair is applied
  when a generated tuple would collide and the repair is recorded in the manifest
  (nothing is silently dropped).

Usage
-----
    python tools/synthetic-data/generate.py --tier ci
    python tools/synthetic-data/generate.py --tier demo --orders 2000
    python tools/synthetic-data/generate.py --tier ci --out %TEMP%/syn-ci   # determinism check
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import sys
import uuid
from decimal import Decimal, ROUND_HALF_UP

TOOL_VERSION = '1.0.0'
TOOL_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(TOOL_DIR, '..', '..'))
SNAPSHOT_PATH = os.path.join(TOOL_DIR, 'schema', 'schema-snapshot.json')
OUT_ROOT = os.path.join(TOOL_DIR, 'out')

DATASET_ID = 'synthetic-amazon-erp-v1'
SEED = 20260924
DATA_ORIGIN = 'SYNTHETIC'
CLOCK_UTC = '2026-09-24T00:00:00Z'          # seed-derived fixed clock (never time.time())
DATE_BASE = '2026-01-01'
ID_BASE = 900000000000000000                # reserved synthetic band (BIGINT columns)
ID_BAND = 100000000
USER_ID_BASE = 100000001                    # small bands: amz_order.user_id is INT
PRODUCT_ID_BASE = 200000001                 # amz_order.product_id is INT
NAMESPACE = uuid.UUID('6f2d1f9a-0f1e-5c3a-9d4b-3a7c2e5b1d10')
BATCH_ROWS = 200

# Official marketplace ids (spec 1.9.2(3) / P0-36).  The synthetic dataset only uses
# ids from this list so that the region resolver never has to fall back to NA.
MARKETPLACES = [
    ('ATVPDKIKX0DER', 'NA', 'US', 'USD'),
    ('A2EUQ1WTGCTBG2', 'NA', 'CA', 'CAD'),
    ('A1AM78C64UM0Y8', 'NA', 'MX', 'MXN'),
    ('A1F83G8C2ARO7P', 'EU', 'GB', 'GBP'),
    ('A13V1IB3VIYZZH', 'EU', 'FR', 'EUR'),
    ('A1PA6795UKMFR9', 'EU', 'DE', 'EUR'),
    ('A1RKKUPIHCS9HS', 'EU', 'ES', 'EUR'),
    ('APJ6JRA9NG5V4', 'EU', 'IT', 'EUR'),
    ('A39IBJ37TRP1C6', 'FE', 'AU', 'AUD'),
    ('A1VC38T7YXB528', 'FE', 'JP', 'JPY'),
    ('A19VAU5U5O7RUS', 'FE', 'SG', 'SGD'),
]

TIERS = {
    'demo': dict(tenants=1, shops=3, marketplaces=3, orders=10000, chaos=False),
    'ci': dict(tenants=1, shops=2, marketplaces=2, orders=1000, chaos=False),
    'staging': dict(tenants=1, shops=10, marketplaces=5, orders=1000000, chaos=False),
    'perf': dict(tenants=10, shops=100, marketplaces=20, orders=10000000, chaos=False),
    'chaos': dict(tenants=1, shops=2, marketplaces=2, orders=2000, chaos=True),
}

# --------------------------------------------------------------------------- #
# row layout: which business coordinates each table enumerates
# --------------------------------------------------------------------------- #
SIMPLE_DIMS = {
    'shop': lambda s: s['shops'],
    'marketplace': lambda s: s['marketplaces'],
    'product': lambda s: s['products_per_shop_market'],
    'user': lambda s: s['users'],
    'warehouse': lambda s: s['warehouses_per_shop'],
    'supplier': lambda s: s['suppliers_per_shop'],
    'purchase_order': lambda s: s['purchase_orders_per_shop'],
    'campaign': lambda s: s['campaigns_per_shop'],
    'keyword': lambda s: s['keywords_per_campaign'],
    'term': lambda s: 10,
    'targeting': lambda s: 3,
    'day': lambda s: 7,
    'day14': lambda s: 14,
    'day3': lambda s: 3,
    'skus5': lambda s: min(5, s['products_per_shop_market']),
    'platform': lambda s: 2,
    'product5': lambda s: min(5, s['products_per_shop_market']),
    'role': lambda s: 3,
    'service': lambda s: 3,
    'entity': lambda s: 2,
    'field': lambda s: 3,
    'category': lambda s: 12,
    'month': lambda s: 12,
    'size_tier': lambda s: 4,
    'weight': lambda s: 4,
    'region': lambda s: 2,
}

LAYOUTS = {
    'amz_user.amz_user': ['user'],
    'amz_user.amz_shop': ['shop', 'marketplace'],
    'amz_user.amz_user_shop': ['user', 'shop'],
    'amz_user.amz_field_permission': ['role', 'service', 'entity', 'field'],
    'amz_product.amz_product': ['shop', 'marketplace', 'product'],
    'amz_order.amz_product_cost': ['shop', 'marketplace', 'product'],
    'amz_order.amz_order': ['shop', 'marketplace', 'product', 'order'],
    'amz_order.amz_order_item': ['shop', 'marketplace', 'product', 'order'],
    'amz_order.amz_order_attribute': ['order'],
    'amz_order.amz_order_split_log': ['order'],
    'amz_order.amz_profit_report': ['order'],
    'amz_order.amz_category_fee_rate': ['category'],
    'amz_order.amz_fba_fee_table': ['size_tier', 'weight', 'region'],
    'amz_order.amz_order_audit_rule': ['shop', 'day3'],
    'amz_procurement.amz_supplier': ['shop', 'supplier'],
    'amz_procurement.amz_supplier_product': ['shop', 'supplier', 'product5'],
    'amz_procurement.amz_purchase_order': ['shop', 'purchase_order'],
    'amz_procurement.amz_purchase_order_item': ['shop', 'purchase_order', 'product5'],
    'amz_procurement.amz_inventory_batch': ['shop', 'purchase_order', 'product5'],
    'amz_procurement.amz_purchase_plan': ['shop', 'product5'],
    'amz_procurement.amz_purchase_approval': ['shop', 'purchase_order'],
    'amz_procurement.amz_quality_check': ['shop', 'purchase_order'],
    'amz_procurement.amz_fba_shipment': ['shop', 'supplier'],
    'amz_procurement.amz_fba_shipment_item': ['shop', 'supplier', 'product5'],
    'amz_logistics.amz_warehouse': ['shop', 'warehouse'],
    'amz_logistics.amz_warehouse_inventory': ['shop', 'warehouse', 'skus5'],
    'amz_logistics.amz_warehouse_stock': ['shop', 'warehouse', 'skus5'],
    'amz_logistics.amz_inbound_order': ['shop', 'warehouse', 'day3'],
    'amz_logistics.amz_outbound_order': ['shop', 'day3', 'day'],
    'amz_logistics.amz_shipment': ['order'],
    'amz_logistics.amz_tracking_event': ['order', 'day3'],
    'amz_logistics.amz_inventory_transfer': ['shop', 'warehouse', 'day3'],
    'amz_logistics.amz_carrier_quote': ['order'],
    'amz_logistics.amz_inventory_alert': ['shop', 'skus5', 'day3'],
    'amz_logistics.amz_fba_receipt_discrepancy': ['shop', 'supplier', 'product5'],
    'amz_logistics.amz_freight_allocation': ['order'],
    'amz_finance.amz_settlement_detail': ['order', 'day3'],
    'amz_finance.amz_payment_collection': ['order'],
    'amz_finance.amz_accounting_voucher': ['order'],
    'amz_finance.amz_fee_discrepancy': ['shop', 'skus5', 'day3'],
    'amz_finance.amz_reimbursement_claim': ['shop', 'skus5', 'day3'],
    'amz_spapi.amz_shop_credential': ['shop', 'marketplace'],
    'amz_spapi.amz_fba_inventory': ['shop', 'marketplace', 'product'],
    'amz_spapi.amz_inventory_sync_log': ['shop', 'day3', 'day'],
    'amz_spapi.amz_sales_history': ['shop', 'skus5', 'day3'],
    'amz_spapi.amz_product_sales_stats': ['shop', 'skus5', 'day3'],
    'amz_spapi.amz_replenishment_suggestion': ['shop', 'skus5', 'day3'],
    'amz_spapi.amz_promotion_calendar': ['category'],
    'amz_spapi.amz_seasonal_index': ['category', 'month'],
    'amz_customer.amz_customer_ticket': ['shop', 'day3', 'day'],
    'amz_customer.amz_email_task': ['shop', 'day3', 'day'],
    'amz_customer.amz_rma': ['shop', 'skus5', 'day3'],
    'amz_customer.amz_negative_review': ['shop', 'skus5', 'day3'],
    'amz_customer.amz_email_template': ['category'],
    'amz_ad.amz_ad_campaign': ['shop', 'campaign'],
    'amz_ad.amz_ad_keyword': ['shop', 'campaign', 'keyword'],
    'amz_ad.amz_ad_daily_report': ['shop', 'campaign', 'day'],
    'amz_ad.amz_ad_search_term': ['shop', 'campaign', 'term'],
    'amz_ad.amz_ad_targeting': ['shop', 'campaign', 'targeting'],
    'amz_ad.amz_ad_asin_keyword': ['shop', 'product5'],
    'amz_multiplatform.amz_platform_account': ['shop', 'platform'],
    'amz_multiplatform.amz_platform_product': ['shop', 'product5'],
    'amz_multiplatform.amz_unified_order': ['order'],
    'amz_multiplatform.amz_webhook_event': ['order'],
    'amz_multiplatform.amz_platform_inventory': ['shop', 'product5'],
    'amz_report.amz_sales_daily': ['shop', 'day14'],
    'amz_report.amz_profit_detail': ['order'],
    'amz_report.amz_business_overview': ['shop', 'day'],
    # --- 第 70 轮补齐：此前 40 张表无任何模拟数据，其中 31 张被主代码引用 --- #
    # 广告域
    'amz_ad.amz_ad_auto_rule': ['shop', 'campaign'],
    'amz_ad.amz_ad_bid_schedule': ['shop', 'campaign', 'day3'],
    'amz_ad.amz_ad_campaign_ext': ['shop', 'campaign'],
    'amz_ad.amz_ad_converting_terms': ['shop', 'campaign', 'term'],
    'amz_ad.amz_ad_creative': ['shop', 'campaign'],
    # AI 域
    'amz_ai.amz_agent_eval_log': ['shop', 'day3', 'day'],
    'amz_ai.amz_conversation_memory': ['user', 'day3'],
    'amz_ai.amz_user_preference': ['user'],
    # 商品 / Listing 域
    'amz_product.amz_buy_box': ['shop', 'marketplace', 'product'],
    'amz_product.amz_competitor_monitor': ['shop', 'product5'],
    'amz_product.amz_keyword_ranking': ['shop', 'product5', 'day3'],
    'amz_product.amz_listing_change_log': ['shop', 'product5', 'day3'],
    'amz_product.amz_listing_copy_task': ['shop', 'product5'],
    'amz_product.amz_listing_health': ['shop', 'marketplace', 'product'],
    'amz_product.amz_translation_cache': ['shop', 'product5'],
    # 运营域
    'amz_ops.amz_hijack_alert': ['shop', 'product5', 'day3'],
    'amz_ops.amz_keyword_rank': ['shop', 'campaign', 'keyword', 'day3'],
    'amz_ops.amz_keyword_research': ['shop', 'term'],
    'amz_ops.amz_negative_review_alert': ['shop', 'product5', 'day3'],
    'amz_ops.amz_selection_opportunity': ['shop', 'category'],
    # 报表域
    'amz_report.amz_cost_allocation': ['shop', 'month'],
    'amz_report.amz_inventory_turnover': ['shop', 'skus5', 'month'],
    'amz_report.amz_profit_snapshot': ['shop', 'day14'],
    # SP-API 域
    'amz_spapi.amz_feed_result_error': ['shop', 'day3', 'day'],
    'amz_spapi.amz_spapi_call_outbox': ['shop', 'day3', 'day'],
    # 多平台域
    'amz_multiplatform.amz_oauth_app': ['shop', 'platform'],
    'amz_multiplatform.amz_oauth_token': ['shop', 'platform'],
    'amz_multiplatform.amz_platform_message': ['shop', 'platform', 'day3'],
    # 其它被引用的表
    'amz_customer.amz_review_solicitation': ['order'],
    'amz_order.amz_shipment_routing': ['shop', 'warehouse', 'day3'],
    'amz_search.amz_history': ['user', 'day3'],
    # --- 补齐无模拟数据的表（QA-01 表覆盖率：112/112；V5 新增 amz_order_item 后为 113/113）--- #
    'amz_spapi.amz_spapi_notification_destination': ['shop', 'marketplace'],
    'amz_spapi.amz_spapi_notification_inbox': ['shop', 'day3', 'day'],
    'amz_spapi.amz_spapi_notification_subscription': ['shop', 'marketplace', 'day3'],
}

# tables whose row count must not be the full cartesian product
SHARE = {
    # order-derived tables: row count = orders * numerator / denominator
    'amz_order.amz_order': (1, 1),
    # 明细行：每订单 2 行（V5 之前 amz_order 结构上只能存 1 件商品，见 V5 迁移头）
    'amz_order.amz_order_item': (2, 1),
    'amz_order.amz_order_attribute': (1, 1),
    'amz_order.amz_order_split_log': (10, 1),
    'amz_order.amz_profit_report': (1, 1),
    'amz_logistics.amz_shipment': (1, 2),
    'amz_logistics.amz_carrier_quote': (1, 4),
    'amz_logistics.amz_freight_allocation': (1, 4),
    'amz_finance.amz_settlement_detail': (1, 2),
    'amz_finance.amz_accounting_voucher': (1, 4),
    'amz_multiplatform.amz_unified_order': (1, 5),
    'amz_multiplatform.amz_webhook_event': (1, 10),
    'amz_report.amz_profit_detail': (1, 2),
    # 索评按订单派生（1/5 订单发一条）：避免从订单池随机取值后撞唯一键，
    # 被唯一键修复改成 '...-1' 而脱离 amz_order.amazon_order_id 域。
    'amz_customer.amz_review_solicitation': (1, 5),
}

STATUS_VOCAB = {
    'status': ['ACTIVE', 'PENDING', 'SUCCESS', 'FAILED'],
    'order_status': ['Unshipped', 'PartiallyShipped', 'Shipped', 'Delivered', 'Canceled'],
    'sync_status': ['SYNCED', 'PENDING', 'FAILED'],
    'state': ['enabled', 'paused', 'archived'],
    'review_status': ['APPROVED', 'REJECTED', 'PENDING'],
    'ticket_status': ['OPEN', 'PENDING', 'RESOLVED', 'CLOSED'],
    'payment_status': ['UNPAID', 'PARTIAL', 'PAID'],
    'approval_status': ['PENDING', 'APPROVED', 'REJECTED'],
    'match_type': ['BROAD', 'PHRASE', 'EXACT'],
    'state_type': ['enabled', 'paused'],
}

PII_COLUMNS = {
    'buyer_name': 'SYNTHETIC BUYER',
    'buyer_email': 'synthetic.buyer',
    'recipient_name': 'SYNTHETIC RECIPIENT',
    'contact_name': 'SYNTHETIC CONTACT',
    'customer_name': 'SYNTHETIC CUSTOMER',
    'supplier_name': 'SYNTHETIC SUPPLIER',
    'approver': 'SYNTHETIC APPROVER',
    'operator': 'SYNTHETIC OPERATOR',
    'handler': 'SYNTHETIC HANDLER',
}

MONEY_HINTS = ('amount', 'price', 'cost', 'fee', 'total', 'balance', 'refund', 'tax', 'profit',
               'revenue', 'discount', 'rebate', 'vat', 'gross', 'net_', 'income', 'expense',
               'allocation', 'value', 'salary', 'payment', 'settlement', 'reimburse')
INT_HINTS = ('quantity', 'qty', 'count', 'days', 'num_', '_num', 'stock', 'available',
             'reserved', 'inbound', 'outbound', 'weight', 'pieces', 'times', 'score', 'rank',
             'level', 'priority', 'version', 'sort', 'index', 'age', 'size')
BOOL_HINTS = ('is_', 'has_', 'enable', 'enabled', 'deleted', 'flag', 'success', 'synced', 'locked')


def rng_for(seed: int, *parts) -> 'random.Random':
    import random
    blob = '|'.join([str(seed)] + [str(p) for p in parts]).encode('utf-8')
    return random.Random(int.from_bytes(hashlib.sha256(blob).digest()[:8], 'big'))


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with io.open(path, 'rb') as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def prod(values):
    out = 1
    for value in values:
        out *= value
    return out


def date_str(offset_days: int, hour: int = 8, minute: int = 0) -> str:
    from datetime import date, timedelta
    day = date.fromisoformat(DATE_BASE) + timedelta(days=int(offset_days))
    return '%s %02d:%02d:00' % (day.isoformat(), hour, minute)


def only_date(offset_days: int) -> str:
    return date_str(offset_days)[:10]
# --------------------------------------------------------------------------- #
# snapshot / scale
# --------------------------------------------------------------------------- #
# columns whose value must be drawn from a pool harvested from another table
REF_COLUMNS = {
    'tenant_id': 'tenant_id',
    'shop_id': 'shop_id',
    'marketplace_id': 'marketplace_id',
    'source_marketplace_id': 'marketplace_id',
    'user_id': 'user_id',
    'buyer_id': 'user_id',
    'owner_id': 'user_id',
    'operator_id': 'user_id',
    'created_by': 'user_id',
    'updated_by': 'user_id',
    'product_id': 'product_id',
    'order_id': 'order_id',
    'amazon_order_id': 'amazon_order_id',
    'warehouse_id': 'warehouse_id',
    'supplier_id': 'supplier_id',
    'purchase_order_id': 'purchase_order_id',
    'campaign_id': 'campaign_id',
    'keyword_id': 'keyword_id',
    'platform_account_id': 'platform_account_id',
    'asin': 'asin',
    'amazon_asin': 'asin',
    'parent_asin': 'asin',
    'competitor_asin': 'asin',
    'sku': 'sku',
    'seller_sku': 'sku',
    'amazon_sku': 'sku',
    'fn_sku': 'sku',
}

# table -> {column: pool} : the values other tables may reference
HARVEST = {
    'amz_user.amz_user': {'id': 'user_id'},
    'amz_user.amz_shop': {'id': 'shop_id'},
    'amz_product.amz_product': {'id': 'product_id', 'asin': 'asin', 'sku': 'sku'},
    'amz_logistics.amz_warehouse': {'id': 'warehouse_id'},
    'amz_procurement.amz_supplier': {'id': 'supplier_id'},
    'amz_procurement.amz_purchase_order': {'id': 'purchase_order_id'},
    'amz_ad.amz_ad_campaign': {'id': 'campaign_id'},
    'amz_ad.amz_ad_keyword': {'id': 'keyword_id'},
    'amz_order.amz_order': {'id': 'order_id', 'amazon_order_id': 'amazon_order_id'},
    'amz_multiplatform.amz_platform_account': {'id': 'platform_account_id'},
}

# dimension that already determines the pool (so no harvested pool is needed)
DIM_FOR_POOL = {
    'shop_id': 'shop',
    'marketplace_id': 'shop',
    'tenant_id': 'shop',
    'product_id': 'product',
    'asin': 'product',
    'sku': 'product',
    'order_id': 'order',
    'amazon_order_id': 'order',
    'user_id': 'user',
    'warehouse_id': 'warehouse',
    'supplier_id': 'supplier',
    'purchase_order_id': 'purchase_order',
    'campaign_id': 'campaign',
    'keyword_id': 'keyword',
    'platform_account_id': None,
}

PRODUCED_POOLS = set()
for _mapping in HARVEST.values():
    PRODUCED_POOLS.update(_mapping.values())

POOL_CAP = 5000            # pools are sampled, not copied, so 10M-row tiers stay usable
CHARSET_SAFE = 'abcdefghijklmnopqrstuvwxyz0123456789'

# Explicit primary-key bands for the tables the World models.  They must match the ids the
# World hands out, otherwise a harvested pool and a row-context reference disagree.
PK_BANDS = {
    'amz_user.amz_user': USER_ID_BASE,
    'amz_product.amz_product': PRODUCT_ID_BASE,
    'amz_user.amz_shop': ID_BASE + 1000,
    'amz_logistics.amz_warehouse': ID_BASE + 200000,
    'amz_procurement.amz_supplier': ID_BASE + 300000,
    'amz_procurement.amz_purchase_order': ID_BASE + 400000,
    'amz_ad.amz_ad_campaign': ID_BASE + 500000,
    'amz_ad.amz_ad_keyword': ID_BASE + 600000,
    'amz_order.amz_order': ID_BASE + 700000,
}

# realistic NULLs: nullable columns that must stay empty for some states
NULL_RULES = {
    'amz_order.amz_order': {'tracking_number': ('order_status', ('Unshipped', 'Canceled'))},
}


def load_snapshot(path: str = SNAPSHOT_PATH) -> dict:
    with io.open(path, encoding='utf-8') as handle:
        raw = json.load(handle)
    tables = {}
    for entry in raw['tables']:
        item = dict(entry)
        item['by_name'] = dict((col['name'], col) for col in item['columns'])
        tables[item['key']] = item
    return {'raw': raw, 'tables': tables, 'sha256': sha256_file(path)}


def build_scale(tier: str, orders=None, shops=None, marketplaces=None) -> dict:
    if tier not in TIERS:
        raise SystemExit('unknown tier %r - choose one of: %s' % (tier, ', '.join(sorted(TIERS))))
    scale = dict(TIERS[tier])
    if orders is not None:
        scale['orders'] = max(1, int(orders))
    if marketplaces is not None:
        scale['marketplaces'] = max(1, int(marketplaces))
    scale['marketplaces'] = min(scale['marketplaces'], len(MARKETPLACES))
    shops_per_marketplace = max(1, int(shops) if shops is not None else scale['shops'])
    scale['shops_per_marketplace'] = shops_per_marketplace
    scale['shop_count'] = shops_per_marketplace * scale['marketplaces']
    scale['shops'] = scale['shop_count']            # SIMPLE_DIMS['shop'] reads this key
    scale['products_per_shop_market'] = 5
    scale['warehouses_per_shop'] = 2
    scale['suppliers_per_shop'] = 2
    scale['purchase_orders_per_shop'] = 3
    scale['campaigns_per_shop'] = 2
    scale['keywords_per_campaign'] = 3
    scale['users'] = max(3, scale['shop_count'] * 2)
    return scale


# --------------------------------------------------------------------------- #
# world: deterministic business coordinates
# --------------------------------------------------------------------------- #
def is_boolean_name(name: str) -> bool:
    """TINYINT columns that carry a boolean meaning rather than an enum/counter."""
    return (name.startswith('is_') or name.startswith('has_') or name == 'success'
            or name.endswith(('_flag', '_enabled', 'enabled', 'deleted', 'locked', 'synced')))


class World(object):
    def __init__(self, scale, chaos=False):
        self.scale = scale
        self.chaos = chaos
        self.marketplaces = []
        for idx, (mid, region, country, currency) in enumerate(MARKETPLACES[:scale['marketplaces']]):
            self.marketplaces.append(dict(idx=idx, marketplace_id=mid, region=region,
                                          country=country, currency=currency))
        self.tenants = [dict(idx=t, tenant_id=ID_BASE + 1 + t, code='SYN-TENANT-%02d' % (t + 1))
                        for t in range(scale['tenants'])]
        self.shops = []
        for slot in range(scale['shop_count']):
            mp = self.marketplaces[slot % len(self.marketplaces)]
            idx = len(self.shops)
            self.shops.append(dict(idx=idx, shop_id=ID_BASE + 1000 + idx,
                                   tenant=self.tenants[idx % len(self.tenants)], marketplace=mp,
                                   name='SYNTHETIC SHOP %03d' % (idx + 1),
                                   seller_id='SYN-SELLER-%04d' % (idx + 1)))
        self.products = []
        for shop in self.shops:
            for k in range(scale['products_per_shop_market']):
                idx = len(self.products)
                self.products.append(dict(idx=idx, product_id=PRODUCT_ID_BASE + idx, shop=shop,
                                          asin='B0SYN%05d' % idx, sku='SYN-SKU-%06d' % idx,
                                          title='SYNTHETIC PRODUCT %06d' % idx))
        self.users = [dict(idx=u, user_id=USER_ID_BASE + u,
                           tenant=self.tenants[u % len(self.tenants)],
                           username='syn-user-%03d' % (u + 1),
                           email='syn-user-%03d@example.invalid' % (u + 1),
                           role=('ADMIN', 'OPERATOR', 'VIEWER')[u % 3]) for u in range(scale['users'])]
        self.warehouses = []
        for shop in self.shops:
            for k in range(scale['warehouses_per_shop']):
                idx = len(self.warehouses)
                self.warehouses.append(dict(idx=idx, warehouse_id=ID_BASE + 200000 + idx, shop=shop,
                                            code='SYN-WH-%04d' % (idx + 1)))
        self.suppliers = []
        for shop in self.shops:
            for k in range(scale['suppliers_per_shop']):
                idx = len(self.suppliers)
                self.suppliers.append(dict(idx=idx, supplier_id=ID_BASE + 300000 + idx, shop=shop,
                                           name='SYNTHETIC SUPPLIER %04d' % (idx + 1)))
        self.purchase_orders = []
        for shop in self.shops:
            for k in range(scale['purchase_orders_per_shop']):
                idx = len(self.purchase_orders)
                self.purchase_orders.append(dict(idx=idx, purchase_order_id=ID_BASE + 400000 + idx,
                                                 shop=shop, no='SYN-PO-%06d' % (idx + 1)))
        self.campaigns = []
        for shop in self.shops:
            for k in range(scale['campaigns_per_shop']):
                idx = len(self.campaigns)
                self.campaigns.append(dict(idx=idx, campaign_id=ID_BASE + 500000 + idx, shop=shop,
                                           name='SYNTHETIC CAMPAIGN %05d' % (idx + 1)))
        self.keywords = []
        for campaign in self.campaigns:
            for k in range(scale['keywords_per_campaign']):
                idx = len(self.keywords)
                self.keywords.append(dict(idx=idx, keyword_id=ID_BASE + 600000 + idx,
                                          campaign=campaign,
                                          text='synthetic keyword %05d' % (idx + 1)))
        self.products_by_shop = self._group(self.products)
        self.campaigns_by_shop = self._group(self.campaigns)
        self.warehouses_by_shop = self._group(self.warehouses)
        self.suppliers_by_shop = self._group(self.suppliers)
        self.purchase_orders_by_shop = self._group(self.purchase_orders)
        self.keywords_by_campaign = {}
        for keyword in self.keywords:
            self.keywords_by_campaign.setdefault(keyword['campaign']['idx'], []).append(keyword)
        self.orders = []
        for i in range(scale['orders']):
            shop = self.shops[i % len(self.shops)]
            shop_products = self.products_by_shop[shop['idx']]
            product = shop_products[(i // len(self.shops)) % len(shop_products)]
            self.orders.append(dict(idx=i, order_id=ID_BASE + 700000 + i,
                                    amazon_order_id='S%03d-%07d-%07d' % (shop['idx'], i // 10000, i),
                                    shop=shop, marketplace=shop['marketplace'], product=product,
                                    user=self.users[i % len(self.users)],
                                    purchase_offset=i % 30))

    @staticmethod
    def _group(items):
        out = {}
        for item in items:
            out.setdefault(item['shop']['idx'], []).append(item)
        return out

    def products_of(self, shop):
        return self.products_by_shop[shop['idx']]

    def campaigns_of(self, shop):
        return self.campaigns_by_shop[shop['idx']]

    def warehouses_of(self, shop):
        return self.warehouses_by_shop[shop['idx']]

    def suppliers_of(self, shop):
        return self.suppliers_by_shop[shop['idx']]

    def purchase_orders_of(self, shop):
        return self.purchase_orders_by_shop[shop['idx']]

    def keywords_of(self, campaign):
        return self.keywords_by_campaign[campaign['idx']]


# --------------------------------------------------------------------------- #
# generator
# --------------------------------------------------------------------------- #
URL_MARKER = 'example.invalid'
JSON_MARKER = '{"synthetic":true}'


def truncate_marked(text: str, limit: int) -> str:
    """Trim a synthetic value to fit CHAR/VARCHAR(limit) without losing its marker.

    verify.py asserts that every free-text / credential / url / json column is still visibly
    synthetic after the round trip, so a plain text[:limit] is not good enough for narrow columns.
    """
    if limit <= 0:
        return ''
    if len(text) <= limit:
        return text
    lowered = text.lower()
    if URL_MARKER in lowered:
        full = 'https://' + URL_MARKER
        if limit >= len(full):
            return full
        if limit >= len(URL_MARKER):
            return URL_MARKER[:limit]
        return text[:limit]
    if 'synthetic' in lowered and limit >= len(JSON_MARKER):
        return JSON_MARKER
    return text[:limit]


class Generator(object):
    def __init__(self, snapshot, world, tier, include_conditional=False):
        self.snapshot = snapshot
        self.world = world
        self.tier = tier
        self.include_conditional = include_conditional
        self.rows = {}
        self.row_counts = {}
        self.pools = {}
        self.uk_buckets = {}
        self.nulls_injected = {}
        self.table_ordinals = dict((name, pos) for pos, name in enumerate(sorted(LAYOUTS)))
        self.generation_order = []
        self.repair_examples = []
        self.repair_total = 0
        self.skipped_conditional = {}
        self.skipped_generated = {}
        self.skipped_tables = []

    # -- layout / cardinality --------------------------------------------- #
    def layout_of(self, key):
        return LAYOUTS[key]

    def dim_cardinality(self, dim, layout):
        if dim == 'marketplace' and 'shop' in layout:
            return 1                                  # already implied by the shop binding
        if dim == 'shop':
            return len(self.world.shops)
        if dim == 'marketplace':
            return len(self.world.marketplaces)
        if dim == 'product':
            return self.world.scale['products_per_shop_market']
        if dim == 'product5' or dim == 'skus5':
            return min(5, self.world.scale['products_per_shop_market'])
        if dim == 'user':
            return len(self.world.users)
        if dim == 'warehouse':
            return self.world.scale['warehouses_per_shop']
        if dim == 'supplier':
            return self.world.scale['suppliers_per_shop']
        if dim == 'purchase_order':
            return self.world.scale['purchase_orders_per_shop']
        if dim == 'campaign':
            return self.world.scale['campaigns_per_shop']
        if dim == 'keyword':
            return self.world.scale['keywords_per_campaign']
        if dim == 'order':
            return len(self.world.orders)
        if dim not in SIMPLE_DIMS:
            raise SystemExit('unknown layout dimension %r' % dim)
        return SIMPLE_DIMS[dim](self.world.scale)

    def row_count(self, key):
        if key in SHARE:
            numerator, denominator = SHARE[key]
            return max(0, len(self.world.orders) * numerator // denominator)
        layout = self.layout_of(key)
        total = 1
        for dim in layout:
            total *= self.dim_cardinality(dim, layout)
        return total

    # -- scheduling -------------------------------------------------------- #
    def needed_pools(self, key):
        entry = self.snapshot['tables'][key]
        layout = self.layout_of(key)
        need = set()
        for col in entry['columns']:
            pool = REF_COLUMNS.get(col['name'])
            if not pool or pool not in PRODUCED_POOLS or pool in self.pools:
                continue
            dim = DIM_FOR_POOL.get(pool)
            if dim is not None and dim in layout:
                continue                              # resolved from the row context
            need.add(pool)
        return need

    def generate(self):
        unknown = [k for k in LAYOUTS if k not in self.snapshot['tables']]
        if unknown:
            raise SystemExit('LAYOUTS refers to tables missing from the snapshot: %s' % unknown)
        remaining = sorted(LAYOUTS)
        for _pass in range(12):
            if not remaining:
                break
            deferred = []
            for key in remaining:
                if self.needed_pools(key):
                    deferred.append(key)
                    continue
                self.generate_table(key)
            if len(deferred) == len(remaining):
                raise SystemExit('unsatisfiable pool dependency: %s' % deferred)
            remaining = deferred
        if remaining:
            raise SystemExit('pool dependency did not converge: %s' % remaining)
        self.skipped_tables = sorted(k for k in self.snapshot['tables'] if k not in self.rows)
        return self

    # -- row context ------------------------------------------------------- #
    def context(self, key, index):
        layout = self.layout_of(key)
        dims = [d for d in layout if not (d == 'marketplace' and 'shop' in layout)]
        cards = [self.dim_cardinality(d, layout) for d in dims]
        ctx = {'table': key, 'index': index, 'rng': rng_for(SEED, key, index),
               'chaos': self.world.chaos}
        coords = {}
        for pos, dim in enumerate(dims):
            stride = prod(cards[pos + 1:])
            coords[dim] = (index // stride) % cards[pos] if cards[pos] else 0
        for dim, coord in coords.items():
            if dim == 'shop':
                ctx['shop'] = self.world.shops[coord]
            elif dim == 'marketplace':
                ctx['marketplace'] = self.world.marketplaces[coord]
            elif dim == 'product' or dim == 'product5' or dim == 'skus5':
                pool = (self.world.products_of(ctx['shop']) if ctx.get('shop')
                        else self.world.products)
                ctx['product'] = pool[coord]
            elif dim == 'user':
                ctx['user'] = self.world.users[coord]
            elif dim == 'warehouse':
                ctx['warehouse'] = self.world.warehouses_of(ctx['shop'])[coord]
            elif dim == 'supplier':
                ctx['supplier'] = self.world.suppliers_of(ctx['shop'])[coord]
            elif dim == 'purchase_order':
                ctx['purchase_order'] = self.world.purchase_orders_of(ctx['shop'])[coord]
            elif dim == 'campaign':
                ctx['campaign'] = self.world.campaigns_of(ctx['shop'])[coord]
            elif dim == 'keyword':
                ctx['keyword'] = self.world.keywords_of(ctx['campaign'])[coord]
            elif dim == 'order':
                ctx['order'] = self.world.orders[coord]
            elif dim in ('day', 'day3', 'day14'):
                ctx['day_offset'] = coord
            elif dim == 'term':
                ctx['term'] = coord
            elif dim == 'targeting':
                ctx['targeting'] = coord
            elif dim == 'platform':
                ctx['platform'] = coord
            elif dim == 'role':
                ctx['role_idx'] = coord
            elif dim == 'service':
                ctx['service_idx'] = coord
            elif dim == 'entity':
                ctx['entity_idx'] = coord
            elif dim == 'field':
                ctx['field_idx'] = coord
            elif dim == 'category':
                ctx['category'] = 'SYN-CATEGORY-%02d' % (coord + 1)
            elif dim == 'month':
                ctx['month'] = coord + 1
            elif dim == 'size_tier':
                ctx['size_tier'] = ('SMALL', 'MEDIUM', 'LARGE', 'OVERSIZE')[coord]
            elif dim == 'weight':
                ctx['weight'] = (0.5, 1.0, 2.0, 5.0)[coord]
            elif dim == 'region':
                ctx['region'] = ('NA', 'EU')[coord % 2]
        order = ctx.get('order')
        if order is not None:
            ctx['shop'] = order['shop']
            ctx['marketplace'] = order['marketplace']
            ctx['product'] = order['product']
            ctx['user'] = order['user']
        if 'shop' in ctx and 'marketplace' not in ctx:
            ctx['marketplace'] = ctx['shop']['marketplace']
        if 'day_offset' not in ctx:
            ctx['day_offset'] = index % 30
        return ctx
    # -- value factory ----------------------------------------------------- #
    def fit(self, value, col):
        typ, args = col['type'], col['args']
        if value is None:
            return None
        if typ in ('TINYINT', 'SMALLINT', 'MEDIUMINT', 'INT', 'BIGINT'):
            number = int(value)
            # 引用列（REF_COLUMNS）绝不允许静默降级：父键是 BIGINT 而本列是窄整型时，
            # `% 2000000000 + 1` 会把 900000000877000000 变成 877000010 这种"看起来
            # 合理"的错误值，门禁在 FK_TARGETS 未覆盖该列时完全看不出来。宁可让生成
            # 直接失败，也不要产出一份"通过校验但引用是假的"数据集。
            if col['name'] in REF_COLUMNS:
                if typ in ('TINYINT', 'SMALLINT') and abs(number) > 127:
                    raise ValueError(
                        'reference column %s is %s but the parent key %r does not fit; '
                        'widen the column in a Flyway migration instead of wrapping it'
                        % (col['name'], typ, number))
                if typ in ('MEDIUMINT', 'INT') and abs(number) > 2000000000:
                    raise ValueError(
                        'reference column %s is %s but the parent key %r does not fit; '
                        'widen the column in a Flyway migration instead of wrapping it'
                        % (col['name'], typ, number))
            else:
                if typ in ('TINYINT', 'SMALLINT') and abs(number) > 127:
                    number = abs(number) % 120 + 1
                if typ in ('TINYINT', 'SMALLINT', 'MEDIUMINT', 'INT') and abs(number) > 2000000000:
                    number = abs(number) % 2000000000 + 1
            if col['unsigned'] and number < 0:
                number = abs(number)
            return number
        if typ in ('DECIMAL', 'NUMERIC'):
            parts = [p for p in args.replace(' ', '').split(',') if p.isdigit()]
            precision = int(parts[0]) if parts else 10
            scale = int(parts[1]) if len(parts) > 1 else 0
            quantum = Decimal(1).scaleb(-scale)
            number = Decimal(str(value)).quantize(quantum, rounding=ROUND_HALF_UP)
            ceiling = (Decimal(10) ** (precision - scale)) - quantum
            if number.copy_abs() > ceiling:
                number = ceiling if number >= 0 else -ceiling
            if col['unsigned'] and number < 0:
                number = number.copy_abs()
            return number
        if typ in ('DOUBLE', 'FLOAT'):
            number = round(float(value), 4)
            if col['unsigned'] and number < 0:
                number = abs(number)
            return number
        text = str(value)
        if typ in ('VARCHAR', 'CHAR') and args.isdigit():
            limit = int(args)
            if len(text) > limit:
                text = truncate_marked(text, limit)
        return text

    def pool_pick(self, pool, ctx):
        values = self.pools.get(pool)
        if not values:
            return None
        return values[ctx['rng'].randrange(len(values))]

    def ref_value(self, name, pool, ctx):
        shop = ctx.get('shop')
        mp = ctx.get('marketplace') or (shop['marketplace'] if shop else None)
        if pool == 'shop_id' and shop:
            return shop['shop_id']
        if pool == 'marketplace_id' and mp:
            return mp['marketplace_id']
        if pool == 'tenant_id':
            if shop:
                return shop['tenant']['tenant_id']
            if ctx.get('user'):
                return ctx['user']['tenant']['tenant_id']
        if pool == 'user_id':
            if ctx.get('user'):
                return ctx['user']['user_id']
            if ctx.get('order'):
                return ctx['order']['user']['user_id']
        if pool in ('product_id', 'asin', 'sku') and ctx.get('product'):
            product = ctx['product']
            return {'product_id': product['product_id'], 'asin': product['asin'],
                    'sku': product['sku']}[pool]
        if pool == 'order_id' and ctx.get('order'):
            return ctx['order']['order_id']
        if pool == 'amazon_order_id' and ctx.get('order'):
            return ctx['order']['amazon_order_id']
        if pool == 'warehouse_id' and ctx.get('warehouse'):
            return ctx['warehouse']['warehouse_id']
        if pool == 'supplier_id' and ctx.get('supplier'):
            return ctx['supplier']['supplier_id']
        if pool == 'purchase_order_id' and ctx.get('purchase_order'):
            return ctx['purchase_order']['purchase_order_id']
        if pool == 'campaign_id' and ctx.get('campaign'):
            return ctx['campaign']['campaign_id']
        if pool == 'keyword_id' and ctx.get('keyword'):
            return ctx['keyword']['keyword_id']
        return self.pool_pick(pool, ctx)

    def text_value(self, name, ctx):
        idx, rng = ctx['index'], ctx['rng']
        if 'json' in name:
            return json.dumps({'synthetic': True, 'index': idx, 'seq': rng.randint(1, 9999)},
                              sort_keys=True, separators=(',', ':'))
        slug = ''.join(ch if ch in CHARSET_SAFE else '-' for ch in name.lower())[:24]
        return 'SYN-%s-%06d' % (slug, idx)

    def special_value(self, name, col, ctx):
        typ, args = col['type'], col['args']
        idx, rng = ctx['index'], ctx['rng']
        shop = ctx.get('shop')
        mp = ctx.get('marketplace') or (shop['marketplace'] if shop else None)
        # Temporal columns are decided by type first: name hints such as ``token_expires_at``
        # would otherwise be matched by the credential rule and emit a non-timestamp string.
        if typ in ('DATE', 'DATETIME', 'TIMESTAMP'):
            if typ == 'DATE':
                return only_date(ctx['day_offset'] + idx % 7)
            return date_str(ctx['day_offset'] + idx % 7, idx % 24, (idx * 7) % 60)
        if name in REF_COLUMNS:
            value = self.ref_value(name, REF_COLUMNS[name], ctx)
            if value is not None:
                return value
        if name == 'currency' and mp:
            return mp['currency']
        if name == 'region' and mp:
            return mp['region']
        if name in ('country', 'ship_country', 'country_code') and mp:
            return mp['country']
        if name in ('marketplace', 'marketplace_id') and mp:
            return mp['marketplace_id']
        if name in ('language', 'locale'):
            return 'en-US'
        if name == 'order_status':
            return ('Unshipped', 'PartiallyShipped', 'Shipped', 'Delivered', 'Canceled')[idx % 5]
        if name in ('fulfillment_channel', 'fulfillment_type'):
            return ('FBA', 'FBM')[idx % 2]
        if name == 'ship_service_level':
            return ('Standard', 'Expedited')[idx % 2]
        if name == 'role' or name == 'role_code':
            return ('ADMIN', 'OPERATOR', 'VIEWER')[idx % 3]
        if name == 'match_type':
            return ('BROAD', 'PHRASE', 'EXACT')[idx % 3]
        if name == 'sentiment':
            return ('POSITIVE', 'NEUTRAL', 'NEGATIVE')[idx % 3]
        if name in ('priority', 'alert_level'):
            return 1 + idx % 3
        if name in ('platform', 'platform_type', 'channel'):
            return ('SHOPIFY', 'WALMART')[idx % 2]
        if name == 'environment':
            return 'test'
        if typ in ('TINYINT', 'SMALLINT', 'INT', 'BIGINT'):
            if is_boolean_name(name):
                return idx % 2
            if name.endswith('_id'):
                return self.pool_pick(name, ctx) or (ID_BASE + idx) % 2000000000 + 1
            if name == 'status':
                return idx % 2
            if name.endswith('_status') or name.endswith('_state'):
                return idx % 4
            if name.endswith('_type') or name.endswith('_level'):
                return 1 + idx % 4
            if any(hint in name for hint in INT_HINTS):
                if 'weight' in name:
                    return int(ctx.get('weight', 1))
                return 1 + idx % 50
            if 'year' == name:
                return 2026
            if 'month' == name:
                return ctx.get('month', 1 + idx % 12)
            return None
        if name == 'status':
            return 'ACTIVE'
        if name.endswith('_status') or name.endswith('_state'):
            vocab = STATUS_VOCAB.get(name) or STATUS_VOCAB.get(name.replace('_state', '_status'))
            if vocab:
                return vocab[idx % len(vocab)]
            return 'SYNTHETIC_STATUS_%s' % 'ABC'[idx % 3]
        if name.endswith('_type') or name in ('type', 'category_type'):
            return 'SYNTHETIC_TYPE_%s' % 'ABC'[idx % 3]
        if name in PII_COLUMNS:
            base = PII_COLUMNS[name]
            if 'email' in name:
                return '%s%04d@example.invalid' % (base, idx % 9999)
            return '%s %04d' % (base, idx % 9999)
        if 'email' in name:
            return 'syn-%s-%05d@example.invalid' % (name.replace('_', '-'), idx % 99999)
        if 'phone' in name or 'mobile' in name:
            return '+1-555-01%02d' % (idx % 100)
        if name.endswith('_ip') or name == 'ip':
            return '192.0.2.%d' % (idx % 254 + 1)
        if any(word in name for word in ('token', 'secret', 'password', 'access_key', 'private_key',
                                         'credential', 'signature')):
            return 'SYNTHETIC-NOT-A-REAL-SECRET-%06d' % (idx % 1000000)
        if 'url' in name or name.endswith('_endpoint'):
            return 'https://example.invalid/%s/%06d' % (name.replace('_', '-'), idx % 1000000)
        if 'tracking_number' == name:
            return '1ZSYN%09d' % (idx % 1000000000)
        if name.endswith('_date') and typ not in ('DATE', 'DATETIME', 'TIMESTAMP'):
            return only_date(ctx['day_offset'])
        if name.endswith('_time') and typ not in ('DATE', 'DATETIME', 'TIMESTAMP'):
            return date_str(ctx['day_offset'], idx % 24, (idx * 7) % 60)
        if name.endswith('_code') or name in ('code', 'no', 'number'):
            return 'SYN-CODE-%06d' % (idx % 1000000)
        if 'hash' in name or name.endswith('_md5') or name.endswith('_sha256'):
            return 'SYNTHETIC-HASH-%08d' % (idx % 100000000)
        if typ in ('TEXT', 'MEDIUMTEXT', 'LONGTEXT'):
            return 'SYNTHETIC %s for row %d - generated test content, not real data.' % (
                name.replace('_', ' '), idx)
        if name.endswith('_name') or name == 'name' or name.endswith('_title') or name == 'title':
            return 'SYNTHETIC %s %06d' % (name.replace('_', ' ').upper()[:40], idx % 1000000)
        if (name.endswith('_reason') or name.endswith('_remark') or name.endswith('_note')
                or name in ('description', 'comment', 'remark', 'message', 'content', 'detail',
                            'failure_reason', 'selected_reason', 'preferred_category')):
            return 'SYNTHETIC-%s-%06d' % (name.replace('_', '-').upper()[:24], idx % 1000000)
        if name == 'version':
            return 1
        return None

    def value(self, col, ctx):
        name = col['name']
        special = self.special_value(name, col, ctx)
        if special is not None:
            return self.fit(special, col)
        typ = col['type']
        idx, rng = ctx['index'], ctx['rng']
        if typ in ('TINYINT', 'SMALLINT', 'MEDIUMINT', 'INT', 'BIGINT'):
            if is_boolean_name(name):
                return 0 if idx % 2 == 0 else 1
            if 'day' in name:
                return 1 + idx % 28
            if 'hour' in name:
                return idx % 24
            if 'percent' in name or 'rate' in name:
                return 1 + idx % 100
            return self.fit(rng.randint(1, 500), col)
        if typ in ('DECIMAL', 'NUMERIC', 'DOUBLE', 'FLOAT'):
            if any(hint in name for hint in ('rate', 'ratio', 'percent', 'margin')):
                return self.fit(round(rng.uniform(0.01, 0.5), 4), col)
            if any(hint in name for hint in MONEY_HINTS):
                return self.fit(round(rng.uniform(1.0, 9999.99), 2), col)
            return self.fit(round(rng.uniform(1.0, 999.99), 2), col)
        if typ == 'DATE':
            return only_date(ctx['day_offset'] + idx % 7)
        if typ in ('DATETIME', 'TIMESTAMP'):
            return date_str(ctx['day_offset'] + idx % 7, idx % 24, (idx * 7) % 60)
        if typ == 'TIME':
            return '%02d:%02d:00' % (idx % 24, idx % 60)
        if typ == 'JSON':
            return json.dumps({'synthetic': True, 'index': idx}, sort_keys=True, separators=(',', ':'))
        if typ in ('BLOB', 'LONGBLOB', 'BINARY', 'VARBINARY'):
            return 'SYNTHETIC-BLOB-%08d' % (idx % 100000000)
        return self.fit(self.text_value(name, ctx), col)

    # -- uniqueness / nullability ------------------------------------------ #
    def mutate(self, value, attempt, col):
        if isinstance(value, (int, float, Decimal)):
            return self.fit(int(value) + attempt, col)
        text = str(value)
        if col['type'] in ('VARCHAR', 'CHAR') and col['args'].isdigit():
            limit = int(col['args'])
            suffix = '-%d' % attempt
            return (text[:max(1, limit - len(suffix))] + suffix)[:limit]
        return '%s-%d' % (text, attempt)

    def enforce_unique(self, key, entry, row):
        keys = list(entry['unique_keys'])
        if entry['primary_key']:
            keys.append({'name': '__primary_key', 'columns': list(entry['primary_key'])})
        for unique in keys:
            columns = [c for c in unique['columns'] if c in row]
            if len(columns) != len(unique['columns']):
                continue
            bucket = self.uk_buckets.setdefault((key, unique['name']), set())
            signature = tuple(row[c] for c in columns)
            if signature in bucket:
                target = columns[-1]
                col = entry['by_name'][target]
                for attempt in range(1, 1001):
                    row[target] = self.mutate(signature[-1], attempt, col)
                    signature = tuple(row[c] for c in columns)
                    if signature not in bucket:
                        break
                self.repair_total += 1
                if len(self.repair_examples) < 20:
                    self.repair_examples.append(dict(table=key, unique_key=unique['name'],
                                                     column=target, duplicated=signature[-1]))
            bucket.add(signature)

    def enforce_not_null(self, key, entry, row):
        for col in entry['columns']:
            if col['name'] not in row or row[col['name']] is not None:
                continue
            if col['nullable']:
                continue
            fallback = self.value(col, {'table': key, 'index': 1, 'rng': rng_for(SEED, key, col['name']),
                                        'chaos': False, 'day_offset': 0})
            if fallback is None:
                raise SystemExit('cannot satisfy NOT NULL for %s.%s' % (key, col['name']))
            row[col['name']] = fallback

    # -- primary keys / null rules ------------------------------------------ #
    def pk_value(self, key, col, index):
        band = PK_BANDS.get(key)
        if band is None:
            ordinal = self.table_ordinals.get(key, 0)
            if col['type'] == 'BIGINT':
                band = ID_BASE + 800000000 + ordinal * 1000000
            else:
                band = 900000000 + ordinal * 100000
        return self.fit(band + index, col)

    def apply_null_rules(self, key, entry, row):
        for column, (trigger, values) in sorted(NULL_RULES.get(key, {}).items()):
            if column not in row or row.get(trigger) not in values:
                continue
            if not entry['by_name'][column]['nullable']:
                continue
            row[column] = None
            self.nulls_injected[column] = self.nulls_injected.get(column, 0) + 1

    # -- table generation --------------------------------------------------- #
    def generate_table(self, key):
        entry = self.snapshot['tables'][key]
        columns = []
        skipped_conditional = []
        skipped_generated = []
        for col in entry['columns']:
            if col['generated']:
                skipped_generated.append(col['name'])
                continue
            if col['conditional'] and not self.include_conditional:
                skipped_conditional.append(col['name'])
                continue
            columns.append(col)
        if skipped_conditional:
            self.skipped_conditional[key] = sorted(skipped_conditional)
        if skipped_generated:
            self.skipped_generated[key] = sorted(skipped_generated)
        total = self.row_count(key)
        rows = []
        for index in range(total):
            ctx = self.context(key, index)
            row = {}
            for col in columns:
                row[col['name']] = self.value(col, ctx)
            if 'id' in row:
                row['id'] = self.pk_value(key, entry['by_name']['id'], index)
            self.apply_null_rules(key, entry, row)
            self.enforce_unique(key, entry, row)
            self.enforce_not_null(key, entry, row)
            rows.append(row)
        self.rows[key] = rows
        self.row_counts[key] = len(rows)
        self.harvest(key)
        self.generation_order.append(key)
        return rows

    def harvest(self, key):
        mapping = HARVEST.get(key)
        if not mapping:
            return
        rows = self.rows[key]
        for column, pool in mapping.items():
            seen = set()
            values = []
            for row in rows:
                value = row.get(column)
                if value is None or value in seen:
                    continue
                seen.add(value)
                values.append(value)
            if len(values) > POOL_CAP:
                step = max(1, len(values) // POOL_CAP)
                values = values[::step][:POOL_CAP]
            self.pools.setdefault(pool, [])
            self.pools[pool].extend(values)

# --------------------------------------------------------------------------- #
# writers
# --------------------------------------------------------------------------- #
def sql_literal(value) -> str:
    if value is None:
        return 'NULL'
    if isinstance(value, bool):
        return '1' if value else '0'
    if isinstance(value, (int, float, Decimal)):
        return str(value)
    text = str(value).replace('\\', '\\\\').replace("'", "''")
    return "'" + text + "'"


def column_names(entry, include_conditional, rows):
    if rows:
        return list(rows[0].keys())
    return [col['name'] for col in entry['columns']
            if not col['generated'] and (include_conditional or not col['conditional'])]


def render_sql(entry, columns, rows, tier) -> str:
    title = '%s.%s' % (entry['database'], entry['name'])
    lines = [
        '-- SYNTHETIC DATA - AmazonERP test dataset. NOT real customer data.',
        '-- dataset_id: %s  seed: %d  tier: %s  data_origin: %s' % (DATASET_ID, SEED, tier, DATA_ORIGIN),
        '-- table: %s  rows: %d  columns: %d' % (title, len(rows), len(columns)),
        '-- generated_by: tools/synthetic-data/generate.py v%s  clock: %s' % (TOOL_VERSION, CLOCK_UTC),
        '-- Load only into a disposable test database; see manifest.json known_schema_conflicts.',
        '',
    ]
    if not rows:
        lines.append('-- no rows generated for this table (not part of the synthetic layout)')
        lines.append('')
        return '\n'.join(lines)
    column_sql = ', '.join('`%s`' % name for name in columns)
    for start in range(0, len(rows), BATCH_ROWS):
        chunk = rows[start:start + BATCH_ROWS]
        lines.append('INSERT INTO `%s` (%s) VALUES' % (entry['name'], column_sql))
        body = ',\n'.join('  (' + ', '.join(sql_literal(row[name]) for name in columns) + ')'
                          for row in chunk)
        lines.append(body + ';')
        lines.append('')
    return '\n'.join(lines)


def render_jsonl(rows) -> str:
    if not rows:
        return ''
    return '\n'.join(json.dumps(row, ensure_ascii=False, sort_keys=True, default=str)
                     for row in rows) + '\n'


def write_text(path: str, text: str) -> dict:
    with io.open(path, 'w', encoding='utf-8', newline='\n') as handle:
        handle.write(text)
    return {'path': os.path.relpath(path).replace('\\', '/'), 'bytes': os.path.getsize(path),
            'sha256': sha256_text(text)}


def reset_directory(path: str) -> None:
    resolved = os.path.abspath(path)
    root = os.path.abspath(OUT_ROOT)
    if len(resolved.split(os.sep)) < 4 or resolved in (os.sep, os.path.expanduser('~')):
        raise SystemExit('refusing to reset %s' % resolved)
    if not resolved.startswith(root) and os.environ.get('SYNTHETIC_ALLOW_EXTERNAL_RESET') != '1':
        raise SystemExit('refusing to reset %s (outside %s); set SYNTHETIC_ALLOW_EXTERNAL_RESET=1 to allow'
                         % (resolved, root))
    if os.path.isdir(resolved):
        import shutil
        shutil.rmtree(resolved)


# --------------------------------------------------------------------------- #
# dataset
# --------------------------------------------------------------------------- #
# P0-40（amz_order.uk_amazon_order 单列唯一键与 spec 7.6 冲突）已于 2026-09-27 由
# amz-service-order V4__order_shop_scoped_identity.sql 解决：唯一键改为
# uk_shop_market_order (shop_id, marketplace_id, amazon_order_id)。此处不再登记。
# 由此带来的后续项：生成器现在可以（但尚未）产出跨店/跨站点同号订单来验证该约束，
# 见 docs/superpowers/runbooks/reference-key-convergence-rollback.md 未闭环项。
KNOWN_SCHEMA_CONFLICTS = [
    {
        'id': 'P0-39',
        'table': 'amz_user.amz_user, amz_purchase_order',
        'constraint': 'ALTER TABLE ... ADD COLUMN IF NOT EXISTS',
        'conflict': ('MySQL 8 (the compose/k8s image is mysql:8.0) does not support ADD COLUMN '
                     'IF NOT EXISTS; 4 statements abort with ERROR 1064'),
        'impact': ('the affected columns stay conditional: they are written only with '
                   '--include-conditional, which is intended for the post-fix schema'),
    },
]


def write_dataset(generator, snapshot, scale, tier, out_dir, include_conditional,
                  truncate_first=False) -> dict:
    sql_dir = os.path.join(out_dir, 'sql')
    jsonl_dir = os.path.join(out_dir, 'jsonl')
    for directory in (out_dir, sql_dir, jsonl_dir):
        if not os.path.isdir(directory):
            os.makedirs(directory)
    tables = []
    sql_names = []
    sql_targets = []
    total_rows = 0
    for position, key in enumerate(generator.generation_order, start=1):
        entry = snapshot['tables'][key]
        rows = generator.rows[key]
        columns = column_names(entry, include_conditional, rows)
        base = '%02d-%s.%s' % (position, entry['database'], entry['name'])
        sql_path = os.path.join(sql_dir, base + '.sql')
        jsonl_path = os.path.join(jsonl_dir, base + '.jsonl')
        sql_info = write_text(sql_path, render_sql(entry, columns, rows, tier))
        jsonl_info = write_text(jsonl_path, render_jsonl(rows))
        sql_names.append(base + '.sql')
        sql_targets.append((entry['database'], entry['name'], base + '.sql'))
        total_rows += len(rows)
        tables.append({
            'table': key,
            'database': entry['database'],
            'name': entry['name'],
            'rows': len(rows),
            'columns': columns,
            'sql': os.path.relpath(sql_path, out_dir).replace('\\', '/'),
            'sql_sha256': sql_info['sha256'],
            'sql_bytes': sql_info['bytes'],
            'jsonl': os.path.relpath(jsonl_path, out_dir).replace('\\', '/'),
            'jsonl_sha256': jsonl_info['sha256'],
            'jsonl_bytes': jsonl_info['bytes'],
        })
    load_order = ['-- SYNTHETIC DATA - AmazonERP test dataset (load order)',
                  '-- usage: cd <dataset dir> && mysql --default-character-set=utf8mb4 -h HOST -u USER -p < load-all.sql',
                  '-- the target schema must already exist (docker/init-sql or Flyway migrations)',
                  '-- every table file is loaded under its own database (USE is emitted on database change)',
                  '-- default mode requires EMPTY dataset tables (first load). For a repeatable load',
                  '-- re-generate with --truncate-first, which prefixes TRUNCATE TABLE statements.',
                  'SET NAMES utf8mb4;']
    if truncate_first:
        load_order.append('')
        load_order.append('-- ============================================================================')
        load_order.append('-- !!! TRUNCATE MODE !!! This script DELETES every row of the tables below.')
        load_order.append('-- !!! SYNTHETIC TEST DATABASES ONLY - NEVER POINT THIS AT A PRODUCTION SCHEMA.')
        load_order.append('-- ============================================================================')
        load_order.append('SET FOREIGN_KEY_CHECKS=0;')
        current_db = None
        for database, table_name, _ in reversed(sql_targets):
            if database != current_db:
                load_order.append('USE `%s`;' % database)
                current_db = database
            load_order.append('TRUNCATE TABLE `%s`;' % table_name)
        load_order.append('SET FOREIGN_KEY_CHECKS=1;')
        load_order.append('')
        load_order.append('-- load data (children before parents is NOT required; no explicit FKs exist)')
    current_db = None
    for database, table_name, name in sql_targets:
        if database != current_db:
            load_order.append('USE `%s`;' % database)
            current_db = database
        load_order.append('SOURCE sql/%s;' % name)
    load_info = write_text(os.path.join(out_dir, 'load-all.sql'), '\n'.join(load_order) + '\n')
    write_text(os.path.join(out_dir, '00-load-order.txt'), '\n'.join(sql_names) + '\n')
    manifest = {
        'dataset_id': DATASET_ID,
        'tool_version': TOOL_VERSION,
        'seed': SEED,
        'clock_utc': CLOCK_UTC,
        'data_origin': DATA_ORIGIN,
        'generated_at': CLOCK_UTC,
        'tier': tier,
        'chaos': bool(generator.world.chaos),
        'include_conditional_columns': bool(include_conditional),
        'truncate_first': bool(truncate_first),
        'scale': scale,
        'schema_snapshot': {
            'path': os.path.relpath(SNAPSHOT_PATH, REPO_ROOT).replace('\\', '/'),
            'sha256': snapshot['sha256'],
            'tool_version': snapshot['raw']['tool_version'],
            'union_table_count': snapshot['raw']['stats']['union_table_count'],
        },
        'tables': tables,
        'totals': {
            'tables_with_rows': len(tables),
            'tables_in_snapshot': len(snapshot['tables']),
            'rows': total_rows,
            'sql_bytes': sum(t['sql_bytes'] for t in tables),
            'jsonl_bytes': sum(t['jsonl_bytes'] for t in tables),
            'load_all_sha256': load_info['sha256'],
        },
        'tables_without_rows': generator.skipped_tables,
        'skipped_conditional_columns': generator.skipped_conditional,
        'skipped_generated_columns': generator.skipped_generated,
        'unique_key_repairs': {'total': generator.repair_total, 'examples': generator.repair_examples},
        'nulls_injected': dict(sorted(generator.nulls_injected.items())),
        'nulls_injected_total': sum(generator.nulls_injected.values()),
        'reference_pools': dict((name, len(values)) for name, values in sorted(generator.pools.items())),
        'known_schema_conflicts': KNOWN_SCHEMA_CONFLICTS,
    }
    write_text(os.path.join(out_dir, 'manifest.json'),
               json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + '\n')
    return manifest


# --------------------------------------------------------------------------- #
# cli
# --------------------------------------------------------------------------- #
def parse_args(argv):
    parser = argparse.ArgumentParser(description='Deterministic synthetic data for AmazonERP')
    parser.add_argument('--tier', default='ci', choices=sorted(TIERS),
                        help='scale profile (default: ci)')
    parser.add_argument('--orders', type=int, help='override the order count')
    parser.add_argument('--shops', type=int, help='override shops per marketplace')
    parser.add_argument('--marketplaces', type=int, help='override the marketplace count')
    parser.add_argument('--out', help='output directory (default: tools/synthetic-data/out/<tier>)')
    parser.add_argument('--include-conditional', action='store_true',
                        help='also write columns that only exist in ALTER TABLE statements (conditional columns)')
    parser.add_argument('--truncate-first', action='store_true',
                        help='prefix load-all.sql with TRUNCATE TABLE (repeatable load; synthetic test DBs only)')
    parser.add_argument('--reset', action='store_true', help='delete the output directory first')
    parser.add_argument('--quiet', action='store_true', help='only print the manifest path')
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    snapshot = load_snapshot()
    scale = build_scale(args.tier, orders=args.orders, shops=args.shops,
                        marketplaces=args.marketplaces)
    out_dir = os.path.abspath(args.out) if args.out else os.path.join(OUT_ROOT, args.tier)
    if args.reset:
        reset_directory(out_dir)
    if args.tier == 'perf':
        print('[generate] WARNING: tier perf builds every row in memory; expect several GB of RAM')
    world = World(scale, chaos=bool(TIERS[args.tier]['chaos']))
    generator = Generator(snapshot, world, args.tier, include_conditional=args.include_conditional)
    generator.generate()
    manifest = write_dataset(generator, snapshot, scale, args.tier, out_dir, args.include_conditional,
                             truncate_first=args.truncate_first)
    if args.quiet:
        print(os.path.join(out_dir, 'manifest.json'))
        return 0
    print('[generate] tier=%s seed=%d shops=%d marketplaces=%d orders=%d' % (
        args.tier, SEED, scale['shop_count'], scale['marketplaces'], scale['orders']))
    print('[generate] tables_with_rows=%d/%d rows=%d sql=%d jsonl=%d' % (
        manifest['totals']['tables_with_rows'], manifest['totals']['tables_in_snapshot'],
        manifest['totals']['rows'], len(manifest['tables']), len(manifest['tables'])))
    print('[generate] tables_without_rows=%d (listed in manifest.tables_without_rows)' % (
        len(manifest['tables_without_rows'])))
    print('[generate] conditional columns written: %s' % manifest['include_conditional_columns'])
    print('[generate] truncate_first=%s' % manifest['truncate_first'])
    print('[generate] unique_key_repairs=%d' % manifest['unique_key_repairs']['total'])
    print('[generate] sql_bytes=%d jsonl_bytes=%d' % (
        manifest['totals']['sql_bytes'], manifest['totals']['jsonl_bytes']))
    print('[generate] out=%s' % out_dir)
    print('[generate] manifest=%s' % os.path.join(out_dir, 'manifest.json'))
    return 0


if __name__ == '__main__':
    sys.exit(main())