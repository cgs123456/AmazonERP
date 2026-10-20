# -*- coding: utf-8 -*-
"""共享样式表与 scoped 副本的等价性门禁（§S33）。

## 这个门禁防的是什么

Vue 的 `<style scoped>` **不跨组件边界**。把一个区块从大组件里拆成子组件时，
它用到的 scoped 类名有两条路：

  1. **搬进子组件**——自包含，但同一套样式会被复制 N 份；
  2. **提成共享样式表**——只留一份，但必须保证搬过去的值与原来**逐字一致**。

走第 2 条路时，失败模式很隐蔽：漏搬一个类、改一个值、丢掉 `@media` 包裹，
**`vue-tsc` / `vitest` / `vite build` 全绿**，用户看到的却是掉样式的界面。
vitest 只断言 DOM 结构与文本，不算样式；`vite build` 只保证 CSS 能编译，
不保证它编译出来的还是同一份样式。

本班在 §S33 实测过两种失败：
  - 漏搬 `.link-btn` / `.truncated-tip`（两者不在全局 style.css 里）→
    「关闭」按钮掉成浏览器默认样式，无任何测试变红；
  - 把 `@media` 里的覆盖规则当成无条件规则搬过去 → 窄屏布局在宽屏生效。

## 判据

对每个「从 scoped 搬到共享样式表」的选择器，比较两边的声明体：

  - 共享样式表里有、原 scoped 里没有 → **漏搬的反面（新增）**，要确认是否有意为之；
  - 原 scoped 里有、共享样式表里没有 → 漏搬；
  - 两边都有但声明体不同 → 改值。

另外单独核对 `@media` 块：共享样式表里的每个媒体查询，其内部规则必须能在
原 scoped 的**同名媒体查询**里找到。这条专门盯上面第二种失败。

## 为什么用解析而不是正则全文匹配

CSS 的声明顺序、分组方式（`.a, .b { }` 与 `.a { } .b { }`）、
单行与多行写法都可能不同，但**语义相同**。按「选择器 → 声明体」归一化后比较，
才不会把排版差异报成回归。归一化会去掉注释与多余空白。
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# 已确认搬到共享样式表的 scoped 副本（scoped 路径, 共享样式表路径, 搬迁前的基线 ref）。
# 只列**确实发生过搬迁**的对；不是把所有 vue 文件都塞进来——
# 那样这个门禁会退化成「全仓 CSS 必须一致」的噪音源。
#
# 第三个元素是**搬迁前**的提交。基线比对要用它而不是 HEAD：
# 搬迁后的 HEAD 里 scoped 块已不含这些规则，拿 HEAD 当基线等于什么都没比。
# 更新规则：每次新的 scoped → 共享样式表搬迁，往这里加一行，
# 基线 ref 填那次搬迁的**父提交**。
MIGRATIONS_WITH_BASE = [
    (
        "amz-frontend/src/views/LogisticsDashboard.vue",
        "amz-frontend/src/styles/logistics-panels.css",
        "bdecf8d~1",  # §S33 抽出 LogisticsTrackingModal 的那次提交之前
    ),
]

# 只关心路径是否存在的检查用这份（不需要基线 ref）
MIGRATIONS = [(a, b) for a, b, _c in MIGRATIONS_WITH_BASE]

# 共享样式表里**有意新增**（非搬迁）的规则，以及新增理由。
# 基线比对会把这些报成"搬迁前不存在"——这是判据应有的严格，
# 但确实存在正当的新增，逐个登记理由，别用通配符糊过去。
#
# 登记标准：只有"修 bug"和"搬迁时必须一并补齐的同类规则"才进这里；
# "顺手加个样式"不进——那该写进视图自己的 scoped 块。
INTENTIONAL_ADDITIONS: dict[str, str] = {
    "amz-frontend/src/styles/logistics-panels.css": (
        ".table-pager: 物流页用了这个类但全仓从未定义（六处同名定义都在别的视图），"
        "属既有缺陷，补齐时与那六处取同值；\n"
        ".muted: 同上，物流页两处用到、无定义，取全仓主流值 "
        "color: var(--color-muted) + font-size-1"
    ),
}

# 有意从视图搬进**子组件**（而非共享样式表）的规则。
# 登记在册的子组件的 scoped 块，会被当作该视图的合法样式来源之一。
#
# 登记标准：该规则只服务于被拆出去的那个子组件的模板。
# 若多个组件都要用，它属于共享样式表，不该登记在这里。
EXTRACTED_TO_COMPONENTS: dict[str, list[str]] = {
    "amz-frontend/src/views/LogisticsDashboard.vue": [
        # §S33：轨迹时间线浮层的样式随组件搬进 LogisticsTrackingModal.vue。
        "amz-frontend/src/components/LogisticsTrackingModal.vue",
        # §S33 之二：调拨面板二次拆分后，`.filter-inline` 随「调拨单列表的状态
        # 筛选器」搬进 LogisticsTransferPanel.vue。登记标准见上方说明——
        # 该规则拆完后全仓库只此一处使用（页壳自己的模板已不再用它），
        # 所以放共享表会让它脱离唯一的使用者。
        "amz-frontend/src/components/LogisticsTransferPanel.vue",
        # §S33.12–16 拆出的其余面板。登记它们是为了让
        # test_no_orphan_rules_left_in_view_after_extraction 能看到「谁在用」——
        # 不登记的话，那条检查无法判断某条规则是「该删」还是「该搬」。
        "amz-frontend/src/components/LogisticsOverviewPanel.vue",
        "amz-frontend/src/components/LogisticsAlertPanel.vue",
        "amz-frontend/src/components/LogisticsShipmentPanel.vue",
        "amz-frontend/src/components/LogisticsCarrierPanel.vue",
        "amz-frontend/src/components/LogisticsImportPanel.vue",
        "amz-frontend/src/components/LogisticsQuoteBoardPanel.vue",
        "amz-frontend/src/components/LogisticsQuoteListPanel.vue",
        "amz-frontend/src/components/LogisticsQuoteComparePanel.vue",
        "amz-frontend/src/components/LogisticsTransferRiskPanel.vue",
        "amz-frontend/src/components/LogisticsFreightPanel.vue",
        "amz-frontend/src/components/LogisticsReceiptPanel.vue",
    ],
}

# 有意删除的规则（模板已不再使用，属清理）。
# 空字典表示"目前没有"——保留它是为了让规则明确：
# 删除要么走这里登记，要么让模板先停止使用那个类。
INTENTIONAL_REMOVALS: dict[str, str] = {}

# **父视图与子组件各自持有一份**的规则。
#
# 背景：`.link-btn` / `.truncated-tip` 这些类父视图和浮层组件都在用，
# 而 scoped 不跨边界，所以两边都得定义。这是"重复"，但是**必要**的重复。
#
# 为什么要单独登记：`test_view_owns_definitions_for_classes_its_template_uses`
# 按类名 token 判存活，因此从共享表删掉 `.link-btn:hover` 时，
# 基础规则 `.link-btn` 还在、token 仍在，那条检查发现不了。
# 把这些"必须两边都有"的选择器列出来，逐个体检——
# 少任何一边就红，比让它从 token 的缝隙里溜过去可靠。
DUPLICATED_BY_DESIGN: dict[str, list[str]] = {
    "amz-frontend/src/views/LogisticsDashboard.vue": [
        # 页壳 19 处使用 link-btn；浮层的「关闭」按钮也用同一个类
        ".link-btn", ".link-btn:hover", ".link-btn:disabled",
        # 页壳与浮层都有「结果被截断」提示
        ".truncated-tip",
    ],
}


# **全局 token 改名**的等价映射：比对前把基线里的旧名换成新名。
#
# 与 INTENTIONAL_ADDITIONS 的区别：那是"新写了一条规则"，这是"同一条规则里的
# 变量名变了"。若逐条登记例外，判据就失去意义——真正的改值会混在例外里溜过去。
# 归一化之后仍然逐字比对，改值照样会红。
#
# 登记标准：改名必须是全仓性的语义等价（同一个变量只换了名字），且改名原因
# 写清楚。局部改值**不准**进这里。
TOKEN_RENAMES: dict[str, str] = {
    # Tailwind v4 的 `rounded-*` 工具类消费 `--radius-*` 命名空间，
    # 项目原名会把 shadcn 组件的圆角一起改掉（Button 变胶囊、Card 24px）。
    # 故项目 token 统一加 `--amz-` 前缀，值与语义均未变。
    "--radius-": "--amz-radius-",
}


def normalize_token_renames(text: str) -> str:
    for old, new in TOKEN_RENAMES.items():
        text = text.replace(f"var({old}", f"var({new}")
    return text


def strip_comments(text: str) -> str:
    return re.sub(r"/\*.*?\*/", "", text, flags=re.S)


def declarations(css: str, top_level_only: bool = False) -> dict[str, str]:
    """把 CSS 解析成 {选择器: 归一化声明体}。

    分组选择器会拆成多条（`.a, .b { x }` → `{.a: x, .b: x}`），
    因为共享样式表可能把它们拆开写，语义不变。

    `top_level_only=True` 时跳过 `@media` 内部的规则。默认 False 是历史行为；
    但**判「是否被拍平」必须传 True**——否则 media 里的规则会被并进同一张表，
    于是「selector 在顶层也有」永远为真，那条检查会退化成永真。
    """
    out: dict[str, str] = {}
    for m in re.finditer(r"([^{}]+)\{([^{}]*)\}", css):
        selector = " ".join(m.group(1).split())
        body = " ".join(m.group(2).split())
        if not selector or selector.startswith("@"):
            continue
        if top_level_only and _inside_media(css, m.start()):
            continue
        for part in selector.split(","):
            part = part.strip()
            if part:
                out[part] = body
    return out


def _inside_media(css: str, pos: int) -> bool:
    """位置 pos 是否落在某个 @media 块内部。"""
    depth = 0
    for m in re.finditer(r"@media[^{]*\{|[{}]", css[:pos]):
        if m.group(0).endswith("{"):
            depth += 1
        else:
            depth -= 1
    return depth > 0


def class_tokens(css: str) -> set[str]:
    """CSS 里出现过的所有类名（含复合选择器里的，如 `.cmp-best.multi` 提供 `multi`）。

    判据必须是**类名 token**而不是完整选择器：模板写 `class="cmp-best multi"`，
    而样式表只写 `.cmp-best.multi { }`——按完整选择器比就会误报"未定义"。
    """
    css = strip_comments(css)
    tokens: set[str] = set()
    for m in re.finditer(r"([^{}]+)\{", css):
        selector = m.group(1)
        if selector.strip().startswith("@"):
            continue
        tokens.update(re.findall(r"\.([A-Za-z_][\w-]*)", selector))
    return tokens


def template_class_tokens(vue_path: Path) -> set[str]:
    """一个 .vue 文件的模板里实际用到的类名。

    用来区分"搬丢"与"该删了"：某条规则的类名若已不在模板里出现，
    删掉它是清理而不是回归，不该报。
    """
    text = vue_path.read_text(encoding="utf-8")
    used: set[str] = set()
    for block in re.finditer(r"<template>\n(.*?)\n</template>", text, re.S):
        tpl = block.group(1)
        for m in re.finditer(r'(?<![:\w-])class="([^"]*)"', tpl, re.S):
            used.update(m.group(1).split())
        for m in re.finditer(r':class="([^"]*)"', tpl, re.S):
            expr = m.group(1)
            for k in re.finditer(r"'([^']*)'\s*:", expr):
                used.update(k.group(1).split())
            for k in re.finditer(r"(?<!:)\s'([^']*)'(?!\s*:)", expr):
                used.update(k.group(1).split())
    return used


def media_blocks(css: str) -> dict[str, dict[str, str]]:
    """{媒体查询条件: {选择器: 声明体}}。专门用来核对响应式覆盖规则。"""
    out: dict[str, dict[str, str]] = {}
    for m in re.finditer(r"@media([^{]*)\{", css):
        condition = " ".join(m.group(1).split())
        i = m.end()
        depth = 1
        while i < len(css) and depth > 0:
            if css[i] == "{":
                depth += 1
            elif css[i] == "}":
                depth -= 1
            i += 1
        out[condition] = declarations(css[m.end():i - 1])
    return out


def scoped_style(vue_path: Path) -> str:
    text = vue_path.read_text(encoding="utf-8")
    m = re.search(r"<style scoped>\n(.*?)\n</style>", text, re.S)
    if not m:
        raise AssertionError(f"{vue_path}: 找不到 <style scoped> 块")
    return m.group(1)


def git_show(rev_and_path: str) -> str | None:
    """取 Git 里某个 revision 的文件内容；取不到返回 None。

    参数是完整的 `<rev>:<path>`，不要自己拼 `HEAD:` 前缀——
    基线比对要的 revision 不一定是 HEAD（搬迁前的提交才是基线）。
    """
    import subprocess
    proc = subprocess.run(
        ["git", "-C", str(ROOT), "show", rev_and_path],
        check=False, capture_output=True)
    if proc.returncode != 0:
        return None
    return proc.stdout.decode("utf-8", errors="replace")


class CssScopeParityTest(unittest.TestCase):
    """共享样式表必须与它替换掉的 scoped 副本逐字一致。"""

    def test_migration_targets_exist(self):
        """登记表里的文件必须都在。少了这条，删掉一个文件只会让门禁静默少查一对。"""
        for scoped_rel, shared_rel in MIGRATIONS:
            self.assertTrue((ROOT / scoped_rel).is_file(), f"缺少 {scoped_rel}")
            self.assertTrue((ROOT / shared_rel).is_file(), f"缺少 {shared_rel}")

    def test_shared_rules_are_not_duplicated_in_scoped(self):
        """共享样式表里的规则不得再留在原 scoped 块中。

        两份都在 = 同一套样式有两个真相来源。改一处漏一处时，
        页面表现取决于哪个文件被后加载——而这一点从类名上看不出来。
        """
        for scoped_rel, shared_rel in MIGRATIONS:
            scoped = declarations(strip_comments(scoped_style(ROOT / scoped_rel)))
            shared = declarations(strip_comments(
                (ROOT / shared_rel).read_text(encoding="utf-8")))
            duplicated = sorted(set(scoped) & set(shared))
            self.assertEqual(
                duplicated, [],
                f"{shared_rel} 与 {scoped_rel} 的 scoped 块重复定义了这些选择器："
                f"{duplicated}——共享样式表的规则必须从 scoped 副本中删除")

    def test_every_selector_used_in_templates_is_defined(self):
        """模板里用到的类名必须有定义来源（共享样式表、全局 style.css 或自身 scoped）。

        这条是 §S33 第二种失败（漏搬 `.link-btn` / `.truncated-tip`）的直接对策。
        当时两个类都不在全局 style.css 里、也忘了搬，结果按钮掉成默认样式。
        """
        checked = 0
        for scoped_rel, shared_rel in MIGRATIONS:
            available = (
                class_tokens((ROOT / "amz-frontend/src/style.css").read_text(encoding="utf-8"))
                | class_tokens((ROOT / shared_rel).read_text(encoding="utf-8"))
                | class_tokens(scoped_style(ROOT / scoped_rel))
            )

            for vue in (ROOT / scoped_rel,):
                text = vue.read_text(encoding="utf-8")
                for block in re.finditer(r"<template>\n(.*?)\n</template>", text, re.S):
                    tpl = block.group(1)
                    used: set[str] = set()
                    # 静态 class="a b"。
                    # 前置 (?<![:\w-]) 是必须的：`class=` 也会匹配到 `:class=` 的尾巴，
                    # 于是动态绑定的整段表达式被当成静态类名表，
                    # 报出一堆 "'{'" "''" "'?'" 之类的假阳性。
                    for m in re.finditer(r'(?<![:\w-])class="([^"]*)"', tpl, re.S):
                        used.update(m.group(1).split())
                    # 动态 :class —— 只认**引号包住的字符串**。
                    # `:class="{ 'kpi-alert': x > 0 }"` 里真正的类名是对象字面量的
                    # **键**；`:class="cond ? 'a' : 'b'"` 里是两个字面量。
                    # 不能宽泛匹配 [\w-]+：那会把值表达式、花括号、比较运算符
                    # 全当成类名，报出上百条假阳性（本班第一版就这么错的，
                    # 报错里出现 "'{' " 和 "'?' " 就是信号）。
                    for m in re.finditer(r":class=\"([^\"]*)\"", tpl, re.S):
                        expr = m.group(1)
                        # 对象字面量形式：'key': ... （key 才是类名）
                        for k in re.finditer(r"'([^']*)'\s*:", expr):
                            used.update(k.group(1).split())
                        # 三元/拼接里的独立字面量（不是对象键）
                        for k in re.finditer(r"(?<!:)\s'([^']*)'(?!\s*:)", expr):
                            used.update(k.group(1).split())
                    missing = sorted(c for c in used if c not in available)
                    self.assertEqual(
                        missing, [],
                        f"{vue.name} 的模板用到这些类，但它们在全局 style.css、"
                        f"{shared_rel} 与该文件的 scoped 块中都没有定义：{missing}。"
                        f"若这是漏搬，样式会静默丢失（没有任何测试会红）。")
                    checked += len(used)
        #  sanity floor：防的是「模板解析口径坏了，一个类都没提取到」，
        # 不是「类变少了」。§S33.14 把 LogisticsDashboard.vue 的模板拆出约 1100 行后，
        # 剩下的类名数掉到 49——那时这条会因为 49 > 50 不成立而误报失败。
        # 所以这里改成两个都必须满足的弱断言：
        #   1. 每个被扫的文件至少提取到 1 个类（解析确实生效）
        #   2. 总量不能掉到「明显没在扫」的程度（8）
        # 拆分会持续减少这个数字，用固定 50 当门槛会把重构本身判成失败。
        self.assertGreater(checked, 8,
                           "一个类都没检查到——模板解析口径坏了")

    def test_no_orphan_rules_left_in_view_after_extraction(self):
        """视图 scoped 块里不得残留「模板已不用、但拆出的子组件在用」的规则。

        **这条补的是一个此前一直漏掉的方向。** 上面那条检查的是
        「模板用到的类有没有定义」——只覆盖「子组件借父视图的样式」。
        反方向从没查过：**样式留在父视图、使用者已经搬进子组件**。
        由于 scoped 不跨组件边界，这些规则对子组件完全无效，
        症状是子组件掉成浏览器默认样式，而所有测试与构建都绿。

        §S33 收尾 review 时实测：LogisticsDashboard.vue 的 scoped 块里有
        31 条规则属于这一类——概览的折线图/状态分布、导入的整个表单与结果卡片、
        货件筛选条，全都静默失效。门禁当时没报，因为它压根不看这个方向。

        判据：某条规则的类名
          1. 不在本视图模板里出现，且
          2. 在登记于 EXTRACTED_TO_COMPONENTS 的某个子组件模板里出现
        即为「孤儿规则」——该规则必须搬进那个子组件（或共享表），不能留在原地。
        """
        for scoped_rel, _shared_rel in MIGRATIONS:
            view = ROOT / scoped_rel
            view_used = template_class_tokens(view)
            # 视图自己用到的类名：留在视图里是合理的，不查
            own_style = class_tokens(scoped_style(view))

            extracted_used: dict[str, set[str]] = {}
            for comp_rel in EXTRACTED_TO_COMPONENTS.get(scoped_rel, []):
                comp = ROOT / comp_rel
                if not comp.exists():
                    continue
                extracted_used[comp.name] = template_class_tokens(comp)

            orphans: dict[str, list[str]] = {}
            for token in sorted(own_style - view_used):
                owners = [name for name, used in extracted_used.items() if token in used]
                if owners:
                    orphans[token] = owners

            self.assertEqual(
                orphans, {},
                f"{view.name} 的 scoped 块里有 {len(orphans)} 条规则，"
                f"视图模板已不用、但拆出的子组件在用：{orphans}。"
                f"scoped 不跨组件边界，这些规则对子组件无效——"
                f"症状是子组件静默掉成默认样式。请把规则搬进对应的子组件"
                f"（或共享样式表），不要留在父视图。")

    def test_shared_table_matches_its_git_original(self):
        """共享样式表必须与**搬迁前**的 scoped 副本一致（以 Git HEAD 为准）。

        前面几条只能发现"多了一份"和"用了没定义"，发现不了这三类
        ——而它们恰恰是本门禁立项时要防的主失败模式：

          1. **漏搬整条规则**：从共享表里删掉 `.link-btn:hover`，
             磁盘上再无第二份定义，"未重复"和"已定义"两条都照样绿；
          2. **改值**：`.kpi-card` 的 background 换个色，
             同样只有一份定义，两条都不红；
          3. **丢掉 @media 包裹**：把响应式覆盖规则写成无条件规则，
             宽屏下会呈现窄屏布局，而两条都不红。

        所以必须有这条"与历史基线比对"。基线取搬迁**前**的那个提交
        （`MIGRATION_BASE_REF`），不是 HEAD——搬迁后的 HEAD 里 scoped 块
        已经不含这些规则了，拿它当基线等于什么都没比。
        """
        for scoped_rel, shared_rel, base_ref in MIGRATIONS_WITH_BASE:
            before = git_show(f"{base_ref}:{scoped_rel}")
            if before is None:
                self.skipTest(f"取不到 {base_ref}:{scoped_rel}，跳过基线比对")
            m = re.search(r"<style scoped>\n(.*?)\n</style>", before, re.S)
            self.assertIsNotNone(m, f"{base_ref} 的 {scoped_rel} 没有 scoped 块")
            # 基线先过一遍 token 改名归一化：全局改名不是改值，不应报成漂移。
            original = declarations(normalize_token_renames(strip_comments(m.group(1))))
            current = declarations(strip_comments(
                (ROOT / shared_rel).read_text(encoding="utf-8")))

            for selector, body in current.items():
                if selector not in original:
                    allowed = INTENTIONAL_ADDITIONS.get(shared_rel, "")
                    self.assertTrue(
                        selector in allowed,
                        f"{shared_rel} 的 {selector} 在搬迁前的 scoped 块里不存在，"
                        f"且未登记进 INTENTIONAL_ADDITIONS。共享样式表只许搬、"
                        f"不许新写——新增规则应回到各视图自己的 scoped 块；"
                        f"若确属修 bug，请在 INTENTIONAL_ADDITIONS 里写明理由")
                    continue
                self.assertEqual(
                    original[selector], body,
                    f"{selector} 的声明体与搬迁前不一致："
                    f"共享表是 [{body}]，原 scoped 是 [{original[selector]}]。"
                    f"共享样式表的职责是原样搬运，不是顺手美化")

            # 反向：共享表里少了的规则，必须仍在**原视图看得见的地方**有定义。
            #
            # "看得见"很关键：Vue 的 scoped CSS 不跨组件边界，所以
            # 子组件里的同名规则**救不了**父视图。本班实测过这个漏洞——
            # 删掉共享表里的 `.link-btn:hover`，子组件里恰好有一条同名的，
            # 宽松判据（"任何来源有这条就行"）就放行了，而父视图 19 处
            # `class="link-btn"` 从此没有 hover 样式。
            #
            # 因此只认三个来源：全局 style.css、原视图自身 scoped、共享表。
            # 合法来源：全局 style.css、视图自身 scoped、共享表，
            # 以及登记在册的「搬进子组件」的 scoped 块。
            #
            # 为什么子组件的也能算：像 `.tl-dot.tone-danger` 这种规则，
            # 它的类名（tone-danger）在父视图模板里仍在用，但**用在不同元素上**
            # （`.kpi-icon.tone-danger`，另有其规则）。整条规则搬去浮层后，
            # 父视图不再需要它——只有"按类名 token 判存活"才会误报。
            surviving = {}
            surviving.update(declarations(strip_comments(
                (ROOT / "amz-frontend/src/style.css").read_text(encoding="utf-8"))))
            surviving.update(declarations(strip_comments(scoped_style(ROOT / scoped_rel))))
            surviving.update(declarations(strip_comments(
                (ROOT / shared_rel).read_text(encoding="utf-8"))))
            for comp_rel in EXTRACTED_TO_COMPONENTS.get(scoped_rel, []):
                surviving.update(declarations(strip_comments(scoped_style(ROOT / comp_rel))))
            # 顶层规则与 @media 内规则必须**分开**比：
            # `declarations()` 不区分层级，而媒体查询里的覆盖规则
            # 恰恰可能与顶层基础规则同名、声明体不同。并成一张表的话，
            # 「`@media` 里的 .chart-row 是否还在」会被顶层那张表遮住。
            #
            # 反向也必须做：共享表里删掉一条规则、子组件里恰好有同名的，
            # 顶层判据会放行（本班实测过的 `.link-btn:hover` 盲区）。
            # 所以按「条件 + 选择器」逐条核对。
            original_media = media_blocks(normalize_token_renames(strip_comments(m.group(1))))
            surviving_media: dict[str, dict[str, str]] = {}
            for src in [
                (ROOT / "amz-frontend/src/style.css").read_text(encoding="utf-8"),
                scoped_style(ROOT / scoped_rel),
                (ROOT / shared_rel).read_text(encoding="utf-8"),
            ] + [scoped_style(ROOT / c) for c in EXTRACTED_TO_COMPONENTS.get(scoped_rel, [])
                 if (ROOT / c).exists()]:
                for cond, rules in media_blocks(strip_comments(src)).items():
                    surviving_media.setdefault(cond, {}).update(rules)

            for selector, body in original.items():
                if surviving.get(selector) == body:
                    continue
                self.fail(
                    f"{selector}（[{body}]）在搬迁前有定义，现在全局 style.css、"
                    f"视图自身 scoped 块、共享表与登记在册的拆出组件里"
                    f"都找不到同一条规则——这是搬丢。若该规则确属不再需要，"
                    f"请在 INTENTIONAL_REMOVALS 里登记理由")

            for cond, rules in original_media.items():
                for selector, body in rules.items():
                    if surviving_media.get(cond, {}).get(selector) == body:
                        continue
                    self.fail(
                        f"@media {cond} 内的 {selector}（[{body}]）在搬迁前有定义，"
                        f"现在全局 style.css、视图自身 scoped、共享表与登记在册的"
                        f"拆出组件里都找不到**同条件、同声明体**的规则——这是搬丢，"
                        f"或者丢了 @media 包裹（后果：窄屏规则在宽屏也生效）。"
                        f"若确属不再需要，请在 INTENTIONAL_REMOVALS 里登记理由")

    def test_view_owns_definitions_for_classes_its_template_uses(self):
        """视图模板用到的类，必须在该视图**看得见**的范围内有定义。

        这是前面几条的兜底，也是唯一能抓住「从共享表删规则」的一条。
        前面的基线比对会把「子组件里恰好有同名规则」当成存活证据而放行——
        本班实测过：删掉共享表的 `.link-btn:hover`，浮层组件里正好有一条，
        基线比对绿了，而父视图 19 处 link-btn 从此没有 hover 样式。

        判据按**选择器**而不是类名：`.kpi-icon.tone-danger` 与
        `.tl-dot.tone-danger` 是两个不同的选择器，前者在后者被搬走后
        仍须有自己的定义。
        """
        for scoped_rel, shared_rel in MIGRATIONS:
            vue = ROOT / scoped_rel
            text = vue.read_text(encoding="utf-8")
            # 视图自身模板用到的「元素 + 类」组合
            combos: set[str] = set()
            for m in re.finditer(r'(?<![:\w-])class="([^"]*)"', text, re.S):
                classes = m.group(1).split()
                for c in classes:
                    combos.add("." + c)
            # 视图看得见的定义来源（不含子组件——scoped 不跨边界）。
            # 判据用**类名 token** 而不是完整选择器：`.status-tag.tone-danger`
            # 定义的是 `tone-danger` 这个修饰类，模板里 `class="status-tag tone-danger"`
            # 用到它，两者都合法。按完整选择器比会把所有修饰类误报成未定义。
            visible = (
                class_tokens((ROOT / "amz-frontend/src/style.css").read_text(encoding="utf-8"))
                | class_tokens(scoped_style(vue))
                | class_tokens((ROOT / shared_rel).read_text(encoding="utf-8"))
            )
            missing = sorted(c for c in combos if c.lstrip(".") not in visible)
            self.assertEqual(
                missing, [],
                f"{vue.name} 的模板用到这些类，但它们在该视图可见的范围"
                f"（全局 style.css + 自身 scoped + 共享样式表）内没有**精确**的"
                f"选择器定义：{missing}。注意子组件的同名规则不算——"
                f"scoped 不跨组件边界。若这是漏搬，样式会静默丢失")

    def test_rules_duplicated_by_design_are_present_on_both_sides(self):
        """登记在册的「必要重复」规则，两边都必须真的存在。

        为什么需要这条：按类名 token 判存活有个盲区——从共享表删掉
        `.link-btn:hover` 后，基础规则 `.link-btn` 还在，token 不消失，
        `test_view_owns_definitions_...` 发现不了。而页壳有 19 处
        `class="link-btn"`，从此全部失去 hover 样式。

        本班实测过这个盲区：变异测试"删一条规则"一度是绿的。
        所以把这些必须两边都有的选择器显式列出，逐个体检。
        """
        for scoped_rel, shared_rel in MIGRATIONS:
            wanted = DUPLICATED_BY_DESIGN.get(scoped_rel, [])
            if not wanted:
                continue
            own = declarations(strip_comments(scoped_style(ROOT / scoped_rel)))
            shared = declarations(strip_comments(
                (ROOT / shared_rel).read_text(encoding="utf-8")))
            for selector in wanted:
                self.assertIn(
                    selector, shared,
                    f"{shared_rel} 缺少 {selector}。它登记在 DUPLICATED_BY_DESIGN 里，"
                    f"意味着页壳与共享表都必须定义它（scoped 不跨组件边界）")
            # 反向不强制：视图自身 scoped 未必还需要（可能已整体搬走），
            # 这里只断言共享表那份必须在——那才是父视图 19 处用到的。

    def test_media_queries_are_preserved_not_flattened(self):
        """共享样式表里的响应式规则必须仍是**真的 @media**，不能被拍平。

        判据不是"selector 同时出现在 media 与顶层"——那是假阳性：
        `.kpi-grid` 本来就该既有基础规则（四列）又有 media 覆盖（两列/一列），
        两者声明体不同、各有其职。

        真正的拍平是：**声明体相同**的规则既在 media 里、又在顶层。
        那种写法意味着有人把 `@media { .x { A } }` 抄成了 `.x { A }`
        加上一条没用的 media 副本，或反过来——无论哪种，
        该规则都会在所有视口下生效，而它本不该。

        （原先还有一条 test_media_queries_match，比的是「共享表 vs 当前
        scoped 块共有的媒体条件」。§S33.17 把最后 3 条 @media 规则也搬进
        子组件后，scoped 块里已无任何媒体查询，那条检查只剩空集合比较，
        恒真。它要防的拍平由本方法（共享表内部）与
        test_shared_table_matches_its_git_original 的 media 分支
        （对搬迁前基线逐条件比对）覆盖，后者更强，故删除。）
        """
        for _scoped, shared_rel, _base in MIGRATIONS_WITH_BASE:
            css = strip_comments((ROOT / shared_rel).read_text(encoding="utf-8"))
            media = media_blocks(css)
            # 必须只要**顶层**规则：把 media 里的也并进来，
            # 每个 selector 都会"同时出现在顶层与 media 里"，这条检查会永真。
            top = declarations(css, top_level_only=True)
            for condition, rules in media.items():
                for selector, body in rules.items():
                    if selector in top and top[selector] == body:
                        self.fail(
                            f"{shared_rel}: {selector} 在 @media {condition} 内与顶层"
                            f"的声明体完全相同（都是 [{body}]）。响应式规则被拍平了——"
                            f"它会在所有视口宽度下生效，包括它本不该生效的宽屏")


if __name__ == "__main__":
    unittest.main()
