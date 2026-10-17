#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""`@ShopScoped` 只能出现在 Controller 方法上（切面可达性门禁）。

Why this exists (HANDOFF §S33.36):
    审计多租户隔离时，在 `AdServiceImpl.createBidSchedule` 上发现了一枚
    **完全无效却看起来有效**的 `@com.amz.annotation.ShopScoped`（fc07f6b 引入）。
    它无效的原因有三层，每一层单独都足以让它变成装饰：

    1. `ShopIdGuardAspect` 的切点是 `@annotation(com.amz.annotation.ShopScoped)`，
       但它按**方法名解析** `@RequestParam`/`@PathVariable` 参数
       （见 `ShopIdParameterResolver`）。Service 方法的参数没有这些 Web 注解，
       于是 `resolve()` 恒返回 null → 切面走「未找到 shopId 参数，跳过」分支。
    2. 即便解析到了，Service 返回值是 `BidSchedule` 而不是 `Result`，
       切面返回的 `Result.failure(...)` 在类型上也回不到调用方。
    3. 该方法还有一层手写的 `isShopAllowedStrict` 校验（这才是真正生效的那道），
       所以**功能上从未漏过**——危险的是读代码的人。

    这类注释性注解比没有注解更糟：`AdController.createBidSchedule` 自身没有
    `@ShopScoped`（shopId 只在请求体里，切面解析不到），它的 javadoc 写着
    「shopId 位于请求体中，@ShopScoped 无法从方法参数解析；必须在调用服务前显式校验」
    ——也就是说**Controller 侧是清醒的**。而 Service 侧多出来的那一枚
    会让下一位读者以为「Service 已经加了切面保护」，从而以为 Controller 的
    显式校验是冗余的、可以去掉。删掉显式校验的那天，这里才真的开始漏。

判据：
    扫描 `amz-service/**/src/main/java`，凡在**非 Controller 类**的方法上
    出现 `@ShopScoped` 或 `@com.amz.annotation.ShopScoped` 即报错。
    Controller 内允许（那是切面的设计目标），Service/Job/Client 内不允许。

    import 语句、注释、以及包名/字符串里出现的字样不算——只认「注解位置」，
    即该行 strip 后以 `@` 开头（或紧跟 `@Override` 等注解块）。

用法：
    python tools/release/test_shoppointer_scope.py
"""
from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE_ROOT = ROOT / "amz-service"

SCOPE_ANNOTATION = re.compile(r"^@(?:\w+\.)*ShopScoped\b")

# 方法声定的起手特征：修饰符 + 返回类型 + 名字 + 左括号。
_METHOD_DECL = re.compile(
    r"^\s*(?:public|protected|private)\s+[\w<>,\s.\[\]]+?\s+\w+\s*\("
)

# 一行里出现 ShopScoped 的其它无害场合。
_IGNORED_LINE = re.compile(r"^\s*(?:import\b|\*|//|/\*)|\bcom\.amz\.annotation\b")


def java_main_sources() -> list[Path]:
    """全部 main 源码（排除 test / target）。"""
    out: list[Path] = []
    for path in SERVICE_ROOT.rglob("src/main/java/**/*.java"):
        parts = {p.lower() for p in path.parts}
        if "target" in parts or "test" in parts:
            continue
        out.append(path)
    return out


def is_controller(path: Path) -> bool:
    """按文件名判 Controller——与仓库其它门禁同一口径。"""
    return path.name.endswith("Controller.java")


def shopscoped_annotation_lines(text: str) -> list[int]:
    """返回「注解位置」上出现 @ShopScoped 的行号（1-based）。

    注解位置 = 该行 strip 后以 `@` 开头。这样能覆盖
    `@Override` 与 `@ShopScoped` 同行/异行的两种写法，同时排除
    import、javadoc 与正文里提到的类名。
    """
    hits: list[int] = []
    for lineno, raw in enumerate(text.splitlines(), start=1):
        stripped = raw.strip()
        if not stripped.startswith("@"):
            continue
        if _IGNORED_LINE.match(raw):
            continue
        # 一行可能有多个注解（`@Override @ShopScoped`），逐个 token 查
        for token in stripped.split():
            if SCOPE_ANNOTATION.match(token):
                hits.append(lineno)
                break
    return hits


def offending(sources: list[Path]) -> list[str]:
    out: list[str] = []
    for path in sources:
        if is_controller(path):
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for lineno in shopscoped_annotation_lines(text):
            out.append(f"{path.relative_to(ROOT)}:{lineno}")
    return out


class ShopScopePlacementTest(unittest.TestCase):
    """`@ShopScoped` 只能放在 Controller 方法上。"""

    def test_shopscoped_is_absent_from_non_controllers(self):
        sources = java_main_sources()
        self.assertGreater(len(sources), 100,
                           "扫描到的 Java 源文件过少，判据可能失效（路径或 rglob 写错）")
        bad = offending(sources)
        self.assertEqual(
            bad, [],
            "这些位置在非 Controller 类上用了 @ShopScoped。该注解由 "
            "ShopIdGuardAspect 按 @RequestParam/@PathVariable 解析 shopId，"
            "而 Service/Job/Client 的方法参数没有这些注解，解析恒为 null、"
            "切面直接放行——注解看着在、实际不设防。"
            "Service 层请改为显式调用 UserContext.isShopAllowedStrict(shopId)"
            "（或 requireShopAllowed），并把本行删掉：\n  "
            + "\n  ".join(bad))

    def test_real_repo_has_controllers_carrying_the_annotation(self):
        """自检：扫描面真的包含 Controller，不是扫了个空集。

        没有这条，上面那条可能因为「一个 Controller 都没扫到」而恒为空。
        """
        controllers = [p for p in java_main_sources() if is_controller(p)]
        self.assertGreater(len(controllers), 5, "没扫到 Controller，扫描口径坏了")
        with_annotation = [
            p for p in controllers
            if shopscoped_annotation_lines(p.read_text(encoding="utf-8", errors="replace"))
        ]
        self.assertGreater(
            len(with_annotation), 10,
            "带 @ShopScoped 的 Controller 少于 10 个——扫描口径可能失效")

    def test_classifier_ignores_import_and_comment_mentions(self):
        """自检：import / javadoc / 正文提到的类名不得计入。"""
        sample = (
            "package com.amz.service.impl;\n"
            "import com.amz.annotation.ShopScoped;\n"
            "/** 这里提到 ShopScoped 但不该算。 */\n"
            "@Override\n"
            "public BidSchedule createBidSchedule(BidSchedule s) {\n"
            "    // ShopScoped 解析不到 Service 参数\n"
            "    return null;\n"
            "}\n"
        )
        self.assertEqual(shopscoped_annotation_lines(sample), [],
                         "import 与注释里的 ShopScoped 被误判成注解了")

    def test_classifier_catches_qualified_and_unqualified_forms(self):
        """自检：限定名与简名两种写法都要抓到，否则检查面会悄悄变窄。"""
        qualified = "@com.amz.annotation.ShopScoped\npublic void a() {}\n"
        self.assertEqual(len(shopscoped_annotation_lines(qualified)), 1)
        plain = "@ShopScoped\npublic void b() {}\n"
        self.assertEqual(len(shopscoped_annotation_lines(plain)), 1)
        inline = "@Override @ShopScoped\npublic void c() {}\n"
        self.assertEqual(len(shopscoped_annotation_lines(inline)), 1)


if __name__ == "__main__":
    sys.exit(unittest.main())
