#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""`@ShopScoped` 只能出现在 Controller 方法上，且必须在切面够得着的地方（可达性门禁）。

Why this exists (HANDOFF §S33.36 / §S33.41):
    §S33.36：审计多租户隔离时，在 `AdServiceImpl.createBidSchedule` 上发现了一枚
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

    §S33.41 追加第二条判据：Controller 内 `@ShopScoped` 也可能够不着。
    切面只认**名为 shopId** 的 `@RequestParam`/`@PathVariable` 参数，所以
    路径参数叫 `id` / `rmaId` / `shipmentId` 时，切面同样走「未找到 shopId 参数，
    跳过」直接放行——注解在、防线不在。`GET /spapi/performance/{id}` 就是这么
    漏的（自增 id + raw_content LONGTEXT，任何登录用户能逐条读遍所有店铺原文）。

    这条**不要求改代码**：本仓这类端点的正确做法是在 service 层逐行校验
    （`requireRuleAccess` / `mustGetAuthorized` / `LogisticsShopGuard` 等，88 个端点
    都是这个形态）。门禁能可靠断言的是**形态**，不是 service 里的内容：
    一个切面够不着的端点，绝不允许自己在方法体里直连 mapper 做增删改查——
    那意味着归属判定无处可去。所以判据是：
      a) 方法体内有店铺判定调用（`isShopAllowed*` / `requireShop*` / `*Access`）；或
      b) 方法体委托给 service（`xxxService.method(...)`）；或
      c) 注释自陈「这枚注解在这里无效、防线在别处」。
    三者全无才报错。`PerformanceController.detail` 就是三者全无的那个
    （`Result.success(performanceMapper.selectById(id))` 直连 mapper）。

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

# 「这条端点另有防线」的可判定信号。
_GUARD_CALL = re.compile(
    r"isShopAllowedStrict|isShopAllowed\b|isShopAllowedByUserOrTrustedService"
    r"|requireShopAllowed|requireShopAccess|requireShopOnRow"
    r"|require\w*Access|mustGetAuthorized\w*|TenantGuard|ShopGuard"
)
# 委托给 service 层：`xxxService.method(` / `xxxServiceImpl.method(`。
_SERVICE_CALL = re.compile(r"\b\w*Service(?:Impl)?\.\w+\s*\(")
# 「写的人知道切面在这里无效」的自陈（javadoc / 注释）。
_SELF_AWARE = re.compile(
    r"ShopScoped[^。\n]{0,40}(?:解析不到|无法|不生效|空转|不起作用|形同|按参数名)"
    r"|(?:解析不到|无法|不生效|空转|不起作用|形同)[^。\n]{0,40}ShopScoped"
)


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


_MAPPING = re.compile(r"@(?:Get|Post|Put|Delete|Patch)Mapping\b")
_METHOD_START = re.compile(
    r"^\s*(?:public|protected|private)\s+[\w<>,\s.\[\]]+?\s+(\w+)\s*\("
)


def _method_block(lines: list[str], start: int) -> tuple[str, str]:
    """从方法签名行起，取 (上方注解+javadoc 块, 方法体文本)。

    方法体取到配平的大括号为止；取不到就退回 45 行——够覆盖绝大多数方法，
    而这里的判据是「有没有出现店铺判定调用」，多取几行只会更宽松、不会误报。
    """
    j = start - 1
    head: list[str] = []
    while j >= 0 and (lines[j].strip().startswith(("@", "*", "//", "/*", "/**"))
                      or lines[j].strip() == ""):
        head.append(lines[j])
        j -= 1
    head_text = "\n".join(reversed(head))

    body: list[str] = []
    depth = 0
    started = False
    k = start
    while k < len(lines) and k < start + 45:
        body.append(lines[k])
        depth += lines[k].count("{") - lines[k].count("}")
        if "{" in lines[k]:
            started = True
        if started and depth <= 0:
            break
        k += 1
    return head_text, "\n".join(body)


def shopscoped_without_reachable_shopid(sources: list[Path]) -> list[str]:
    """Controller 内 `@ShopScoped` 切面够不着店铺的端点清单。

    「够得着」= 方法签名里有名为 shopId 的参数。其余情况切面直接放行，
    必须另有防线（方法体里的店铺判定）或自陈（注释说明为何注解无效）。
    """
    out: list[str] = []
    for path in sources:
        if not is_controller(path):
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        lines = text.splitlines()
        for i, raw in enumerate(lines):
            m = _METHOD_START.match(raw)
            if not m:
                continue
            head, body = _method_block(lines, i)
            # 只在该方法上方的注解行里找；`re.match` 锚在行首，故先 strip 再拼。
            if not any(SCOPE_ANNOTATION.match(l.strip())
                       for l in head.splitlines() if l.strip().startswith("@")):
                continue
            if not _MAPPING.search(head):
                continue
            # 签名（可能跨行）
            sig = raw
            k = i
            while ")" not in sig and k + 1 < len(lines):
                k += 1
                sig += " " + lines[k].strip()
            if re.search(r"\bshopId\b", sig):
                continue  # 切面从 @RequestParam/@PathVariable 解析得到
            if _GUARD_CALL.search(body):
                continue  # 方法内有逐行店铺判定
            if _SELF_AWARE.search(head + "\n" + body):
                continue  # 注释已说明「这枚注解在这里无效、防线在别处」
            if "@RequestBody" in sig and re.search(r"\.(?:get|set)ShopId\s*\(", body):
                continue  # shopId 在请求体实体里，切面读得到（ShopIdParameterResolver.matchBody）
            if _SERVICE_CALL.search(body) and _addresses_a_row(sig):
                # 委托给 service 且这次调用面向某条具体记录——service 按本仓惯例
                # 逐行校验归属。**必须同时满足**：无状态计算器（如 VAT 的
                # calculate/rate/threshold-check）也委托 service，但它们不面向任何
                # 记录，service 里没有可校验的 shopId，注解同样是装饰。
                continue
            out.append(f"{_display(path)}::{m.group(1)}")
    return out


# 「这次调用面向某条具体记录」：签名里有 id 类路径/查询参数，或整个请求体实体。
_ROW_PARAM = re.compile(
    r"@(?:RequestParam|PathVariable)(?:\([^)]*\))?\s*(?:final\s+)?"
    r"(?:Long|Integer|String)\s+(?:id|ids|\w*Id)\b"
    r"|@RequestBody\s"
)


def _addresses_a_row(sig: str) -> bool:
    """方法是否面向某条具体记录（决定「委托给 service」能否算作防线）。

    VAT 三个计算端点（amount + country）不面向记录 → 返回 False，
    `@ShopScoped` 在它们身上是装饰，必须报出来。
    `/{id}` / `/{shopId}` / `@RequestBody` 实体 → True。
    """
    return bool(_ROW_PARAM.search(sig))


def _display(path: Path) -> str:
    """仓库内用相对路径，仓库外（自检探针）退回文件名。

    `relative_to` 对不在 ROOT 下的路径会抛 ValueError，而自检探针写在临时目录里。
    """
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return path.name



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

    def test_shopscoped_without_reachable_shopid_must_show_another_defense(self):
        """§S33.41：切面够不着 shopId 的端点，必须另有防线或自陈。

        真实缺陷：`GET /spapi/performance/{id}` 标了 `@ShopScoped`，但路径参数叫
        `id`，切面解析不到就走放行分支；`amz_shop_performance.id` 自增、
        `raw_content` 是整份报表原文，于是任何登录用户按 id 递增读遍所有店铺。

        本仓这类端点的**正确形态**是 service 层逐行校验（91 个端点都是），
        所以门禁不要求删注解，要求的是「别让注解单独撑场面」。
        """
        sources = java_main_sources()
        self.assertGreater(len(sources), 100, "扫描面失效")
        bare = shopscoped_without_reachable_shopid(sources)
        self.assertEqual(
            bare, [],
            "这些 Controller 方法的 @ShopScoped 切面够不着（签名里没有名为 shopId 的"
            "参数，ShopIdParameterResolver 解析不到就放行），而方法体里看不到任何"
            "店铺判定调用、注释也没有说明防线在别处。请在 service 层补逐行校验"
            "（isShopAllowedStrict / requireShopAllowed / requireXxxAccess），"
            "或在 javadoc 里写明「这枚注解在这里无效、防线在 X」：\n  "
            + "\n  ".join(bare))

    def test_reachability_classifier_spots_the_bare_form(self):
        """自检：裸 `@ShopScoped` + 只有 id 参数 + 无防线，必须被抓到。"""
        bare = (
            "@RestController\n"
            "class XController {\n"
            "    @ShopScoped\n"
            "    @GetMapping(\"/{id}\")\n"
            "    public Result<Foo> detail(@PathVariable Long id) {\n"
            "        return Result.success(mapper.selectById(id));\n"
            "    }\n"
            "}\n"
        )
        found = shopscoped_without_reachable_shopid([_write_probe(bare)])
        self.assertEqual([f.split("::")[-1] for f in found], ["detail"],
                         "裸 @ShopScoped 端点必须被抓到，实际：" + str(found))

    def test_reachability_classifier_accepts_the_two_valid_forms(self):
        """自检：有 shopId 参数 / 方法体有店铺判定 / 注释自陈，三种都不得报。"""
        with_shop_id = (
            "@RestController\nclass AController {\n"
            "    @ShopScoped\n    @GetMapping(\"/{shopId}\")\n"
            "    public Result<Foo> list(@PathVariable Long shopId) {\n"
            "        return Result.success(mapper.selectList(null));\n    }\n}\n"
        )
        self.assertEqual(shopscoped_without_reachable_shopid([_write_probe(with_shop_id)]), [])

        guarded = (
            "@RestController\nclass BController {\n"
            "    @ShopScoped\n    @PostMapping(\"/{id}/x\")\n"
            "    public Result<Foo> act(@PathVariable Long id) {\n"
            "        if (!UserContext.isShopAllowedStrict(shopOf(id))) {\n"
            "            return Result.failure(\"no\");\n        }\n"
            "        return Result.success(null);\n    }\n}\n"
        )
        self.assertEqual(shopscoped_without_reachable_shopid([_write_probe(guarded)]), [])

        self_aware = (
            "@RestController\nclass CController {\n"
            "    /** @ShopScoped 在这里解析不到 shopId，防线在 service 层逐行做。 */\n"
            "    @ShopScoped\n    @GetMapping(\"/{id}\")\n"
            "    public Result<Foo> get(@PathVariable Long id) {\n"
            "        return Result.success(svc.get(id));\n    }\n}\n"
        )
        self.assertEqual(shopscoped_without_reachable_shopid([_write_probe(self_aware)]), [])

    def test_stateless_calculator_delegating_to_service_is_still_flagged(self):
        """自检：委托 service 但**不面向任何记录**时，不得算作有防线。

        §S33.45 的变异教训：第一版判据把「委托给 service」直接当成防线，
        于是 VAT 的 `calculate(amount, country)` 也被放过——它调
        `vatService.calculateVat(...)`，但那是无状态计算器，service 里没有
        可校验的 shopId，注解同样是装饰。收紧为「委托 service **且**面向某条记录」。
        """
        stateless = (
            "@RestController\nclass VatController {\n"
            "    @ShopScoped\n    @GetMapping(\"/calculate\")\n"
            "    public Result<String> calculate(@RequestParam BigDecimal amount,\n"
            "                                     @RequestParam String country) {\n"
            "        return Result.success(vatService.calculateVat(amount, country));\n"
            "    }\n}\n"
        )
        found = shopscoped_without_reachable_shopid([_write_probe(stateless)])
        self.assertEqual([f.split("::")[-1] for f in found], ["calculate"],
                         "无状态计算器上的 @ShopScoped 必须被抓到，实际：" + str(found))

        # 对照组：同样委托 service，但面向具体记录 → 不得报（防线按惯例在 service 层）
        row_addressed = (
            "@RestController\nclass DController {\n"
            "    @ShopScoped\n    @PostMapping(\"/{id}/cancel\")\n"
            "    public Result<Boolean> cancel(@PathVariable Long id) {\n"
            "        return Result.success(orderService.cancel(id));\n    }\n}\n"
        )
        self.assertEqual(shopscoped_without_reachable_shopid([_write_probe(row_addressed)]), [])


_PROBE_DIR: list[Path] = []


def _write_probe(source: str) -> Path:
    """把一段 Controller 源码写进临时目录，按 Controller 命名规则命名。

    扫描器按文件名判 Controller（`*Controller.java`），所以探针必须叫这个名字；
    输出路径用 `relative_to` 兜不住（不在仓库内），故返回时统一成
    `ProbeController.java::{method}` 的形状由调用方拼——这里只负责落盘。
    """
    import tempfile

    if not _PROBE_DIR:
        _PROBE_DIR.append(Path(tempfile.mkdtemp(prefix="shoppointer-probe-")))
    path = _PROBE_DIR[0] / "ProbeController.java"
    path.write_text(source, encoding="utf-8")
    return path


if __name__ == "__main__":
    sys.exit(unittest.main())
