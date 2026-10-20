# -*- coding: utf-8 -*-
"""CryptoTypeHandler 列清单必须与实体注解保持一致。

为什么需要这个测试：
    `amz-common` 的 `CryptoTypeHandler` 在读取时无条件解密列值。
    合成数据一旦往这些列填了假密文，命中该列的**任何** SELECT 都会
    整行抛异常——非 base64 文本报 `Illegal base64 character`，
    假 base64 报 `AES-256-GCM 解密失败`（实测两个都见过）。
    连锁后果：`ShopMapper.selectList` 崩 →
    `UserServiceImpl.resolveShopOptions` 被 try/catch 吞掉 →
    `/user/getInfo` 不返回 shops → 前端店铺选择器空白 →
    依赖 shopId 的接口全部报错。

    生成器靠 `CRYPTO_PLAINTEXT_COLUMNS` 白名单特判这些列（产 NULL）。
    白名单是手维护的，将来有人在实体上新增一个 CryptoTypeHandler 列，
    很容易忘记同步这里——那时症状是「某个列表莫名查不出来」，
    极难回溯到数据生成器。所以拿实体源码反查白名单，漏一个就红。
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

import generate

REPO = Path(generate.__file__).resolve().parents[2]
JAVA_SOURCES = (
    'amz-common/src/main/java',
    'amz-service',
)

# @TableField(value = "spapi_refresh_token", typeHandler = CryptoTypeHandler.class)
# @TableField("spapi_refresh_token") 之后换行再写 typeHandler 的两种形态都要认。
_TABLE_FIELD_COLUMN = re.compile(
    r'@TableField\s*\(?\s*(?:value\s*=\s*)?"([a-z0-9_]+)"'
)
_CRYPTO_HANDLER = re.compile(r'typeHandler\s*=\s*CryptoTypeHandler\.class')


def _entity_crypto_columns() -> set:
    """从实体源码扫出所有声明了 CryptoTypeHandler 的列名。"""
    columns = set()
    for base in JAVA_SOURCES:
        root = REPO / base
        if not root.is_dir():
            continue
        for path in root.rglob('*.java'):
            if '/target/' in path.as_posix() or '\\target\\' in str(path):
                continue
            text = path.read_text(encoding='utf-8', errors='replace')
            for block in text.split('@TableField'):
                # 同一批注块内同时出现列名与 CryptoTypeHandler 才算命中
                head = block[:400]
                column = _TABLE_FIELD_COLUMN.search('@TableField' + head)
                if column and _CRYPTO_HANDLER.search(head):
                    columns.add(column.group(1))
    return columns


class CryptoColumnWhitelistTest(unittest.TestCase):
    def test_whitelist_covers_every_entity_annotation(self):
        """实体上每一条 CryptoTypeHandler 注解，白名单里都必须有对应列名。"""
        declared = _entity_crypto_columns()
        self.assertTrue(
            declared,
            '没有从实体扫到任何 CryptoTypeHandler 列——是正则失效还是代码被重构了？'
            '若后者，请同步更新本测试的判定方式，不要直接删断言。',
        )
        missing = declared - set(generate.CRYPTO_PLAINTEXT_COLUMNS)
        self.assertEqual(
            missing,
            set(),
            '下列列在实体上挂了 CryptoTypeHandler，但未登记到 '
            'generate.CRYPTO_PLAINTEXT_COLUMNS：%s。'
            '合成数据会往它们填非 base64 文本，导致命中它们的 SELECT 整行失败。'
            % sorted(missing),
        )

    def test_whitelist_has_no_dead_entries(self):
        """反向：白名单里不该有没有对应实体注解的列。"""
        declared = _entity_crypto_columns()
        stale = set(generate.CRYPTO_PLAINTEXT_COLUMNS) - declared
        self.assertEqual(
            stale,
            set(),
            '白名单登记了实体上已不存在的列：%s。删掉它们，'
            '否则将来有人按这份清单反查会以为那些列还在加密。' % sorted(stale),
        )

    def test_crypto_columns_emit_null_not_fake_ciphertext(self):
        """加密列必须一路落到 NULL——这是修复的判据。

        为什么不是「生成合法 base64」：假 base64 能过字符集校验，
        但 AES-256-GCM 解密必失败，整行 SELECT 照样抛异常。
        只有 NULL 能让 CryptoTypeHandler 跳过解密（它只处理非 null 值）。

        断言放在 **value()** 而不是 special_value()：special_value 返回哨兵
        MUST_BE_NULL，只有 value() 会把它转成真正的 None。
        曾经 special_value 直接 return None，被 value() 的
        `if special is not None` 判成"无特殊规则"，继续填了假密钥——
        所以只测 special_value 会漏掉那一层。
        """
        import random

        generator = generate.Generator(snapshot={}, world=None, tier='demo')
        for column in sorted(generate.CRYPTO_PLAINTEXT_COLUMNS):
            col = {'type': 'varchar', 'args': ['255'], 'name': column}
            ctx = {'index': 7, 'rng': random.Random(7)}

            special = generator.special_value(column, col, ctx)
            self.assertIs(
                special,
                generate.MUST_BE_NULL,
                '%s 应返回 MUST_BE_NULL 哨兵，实际是 %r' % (column, special),
            )

            value = generator.value(col, ctx)
            self.assertIsNone(
                value,
                '%s 经 value() 后应落为 NULL，实际是 %r。'
                '非 NULL 假值会让命中该列的 SELECT 整行解密失败。' % (column, value),
            )


if __name__ == '__main__':
    unittest.main(verbosity=2)
