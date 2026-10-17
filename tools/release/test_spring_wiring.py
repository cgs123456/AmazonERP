#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Spring 注入点可解析性门禁。

Why this exists (HANDOFF §S33.20):
    §S32 把 `ErpToolExecutor`（类上有 `@Component`）里的 30 个工具方法按依赖族
    拆到 7 个新类。新类**只加了 `@Slf4j`，没有任何构造型注解**；同时把
    `ErpToolExecutor` 的字段改成了 `@Autowired`（required=true）。

    容器里没有这 7 个类型的 Bean → 启动 `NoSuchBeanDefinitionException`
    → AI 服务起不来。而 41 条单测全是手工 `new ErpAdTools()` +
    `ReflectionTestUtils.setField(...)`，**没有一条经过 Spring 容器**，
    所以 `mvn test` 全绿、`mvn compile` 全绿。缺陷在仓库里活了一整天。

判据（必要条件，不是充分条件）：
    对每个「本身是 Bean」的类，检查它的 `@Autowired` / `@Resource` 字段：
    字段类型若也在本仓 `src/main/java` 下定义，且类型自身没有任何
    「成为 Bean」的途径（构造型注解），就报错。

    豁免：
      - 接口 / record / 枚举 —— Feign 客户端接口由 `@EnableFeignClients`
        生成代理，是另一套机制；
      - `required = false` —— 缺席是设计允许的；
      - 类型不在本仓（JDK / 第三方）。

    抓不住的情况（明确写在测试里，避免下一班误以为它更全能）：
      - 注解在、但组件扫描路径没覆盖到；
      - 条件装配（`@ConditionalOn...`）判定为 false；
      - 同一类型有多个 Bean 导致歧义。
    这些要真起 Spring 容器才能验，而本机与 CI 的 DB/Redis/Feign 依赖不稳定
    （§S30 已定案 loopback 不修）。宁可要一个稳定且能抓住真实发生过的那一类
    的门禁，也不要一个跑不起来的「完整」检查。

用法：
    python -m unittest tools.release.test_spring_wiring
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE_ROOT = ROOT / "amz-service"

# 能让类被组件扫描注册的注解。刻意用「注解全名后缀」匹配，避免误伤注释里的字符串。
BEAN_ANNOTATIONS = (
    "@Component",
    "@Service",
    "@Repository",
    "@Controller",
    "@RestController",
    "@Configuration",
)

# 字段类型本身是容器 / 集合，不按类名解析。
IGNORED_TYPE_NAMES = {
    "List", "Map", "Set", "Optional", "Collection", "Iterable",
    "String", "Long", "Integer", "Boolean", "Object",
}

_CLASS_DECL = re.compile(
    r"(?m)^(?:public\s+)?(?:abstract\s+)?(?:final\s+)?(?:static\s+)?"
    r"(class|interface|record|enum)\s+(\w+)"
)

# `@Autowired` / `@Autowired(required = false)` / `@Resource` 后紧跟字段声明。
_INJECTED_FIELD = re.compile(
    r"@(?:Autowired|Resource)\s*(?:\([^)]*\))?\s*"
    r"(?:private|protected|public)?\s*"
    r"([\w.$]+(?:\s*<[^;=]*?>)?)\s+(\w+)\s*[;=]"
)

# 字段上方的注解块里出现这些，说明「缺席是被允许的」。
_OPTIONAL_MARKERS = ("required = false", "required=false")


def java_sources() -> list[Path]:
    """本仓全部 main 源码（排除 test / target / 迁移脚本）。"""
    out = []
    for path in SERVICE_ROOT.rglob("src/main/java/**/*.java"):
        parts = {p.lower() for p in path.parts}
        if "target" in parts or "test" in parts:
            continue
        out.append(path)
    return out


def index_classes(sources: list[Path]) -> dict[str, list[tuple[Path, str, str]]]:
    """类名 → [(文件, 全文, 声明种类)]。同名类在多个模块里是合法的（各自独立服务）。"""
    index: dict[str, list[tuple[Path, str, str]]] = {}
    for path in sources:
        text = path.read_text(encoding="utf-8", errors="replace")
        for m in _CLASS_DECL.finditer(text):
            index.setdefault(m.group(2), []).append((path, text, m.group(1)))
    return index


def _head_before_declaration(text: str, name: str) -> str:
    """类声明之前的文本（注解都在这里）。"""
    idx = text.find(f"class {name}")
    if idx < 0:
        idx = text.find(f"interface {name}")
    if idx < 0:
        idx = text.find(f"record {name}")
    if idx < 0:
        return text
    return text[:idx]


def is_bean(entry: tuple[Path, str, str]) -> bool:
    """该类型是否有「成为 Bean」的途径。

    接口 / record / enum 一律豁免：Feign 客户端接口由 `@EnableFeignClients`
    生成代理，record 是值对象，都不会被 `@Autowired` 成 Bean。
    """
    path, text, kind = entry
    if kind != "class":
        return True
    head = _head_before_declaration(text, path.stem)
    return any(annotation in head for annotation in BEAN_ANNOTATIONS)


def is_bean_host(text: str) -> bool:
    """这个文件里是否定义了至少一个 Bean（决定要不要检查它的注入点）。"""
    return any(annotation in text for annotation in BEAN_ANNOTATIONS)


def injected_fields(text: str) -> list[tuple[str, str, str]]:
    """[(原始注解片段, 类型, 字段名)]。"""
    out = []
    for m in _INJECTED_FIELD.finditer(text):
        raw = m.group(0)
        type_name = re.sub(r"<.*", "", m.group(1)).split(".")[-1].strip()
        out.append((raw, type_name, m.group(2)))
    return out


class SpringWiringTest(unittest.TestCase):
    """`@Autowired` 的字段类型必须真的有办法成为 Bean。"""

    def test_injected_types_are_beans(self):
        sources = java_sources()
        self.assertGreater(len(sources), 100,
                           "扫描到的 Java 源文件过少，判据可能失效（路径或 rglob 写错）")
        index = index_classes(sources)

        offenders = []
        for path in sources:
            text = path.read_text(encoding="utf-8", errors="replace")
            if not is_bean_host(text):
                continue
            for raw, type_name, field_name in injected_fields(text):
                if type_name in IGNORED_TYPE_NAMES:
                    continue
                entries = index.get(type_name)
                if not entries:
                    continue  # JDK / 第三方 / 生成代码
                if any(is_bean(e) for e in entries):
                    continue
                if any(marker in raw for marker in _OPTIONAL_MARKERS):
                    continue
                offenders.append(
                    f"{path.relative_to(ROOT)}::{field_name} 的类型 {type_name} "
                    f"（定义于 {entries[0][0].relative_to(ROOT)}）"
                )

        self.assertEqual(
            offenders, [],
            "这些 @Autowired(required=true) 字段的类型在本仓有定义，但类型本身"
            "没有任何构造型注解（@Component/@Service/@Repository/@Controller/"
            "@Configuration），容器启动时会 NoSuchBeanDefinitionException。"
            "若缺席是被允许的，改成 @Autowired(required = false)：\n  "
            + "\n  ".join(offenders))

    def test_scanner_catches_a_removed_annotation(self):
        """自检：把已知 Bean 的注解摘掉后，判据必须能报出来。

        没有这条，上面那条可能因为正则失效而**永远为空**——一个恒真的门禁
        比没有门禁更危险，因为它会让人以为已经守住了（§S33 立项的教训）。
        """
        probe = "package x;\n@Slf4j\npublic class ErpAdTools {\n}\n"
        entry = (Path("ErpAdTools.java"), probe, "class")
        self.assertFalse(is_bean(entry), "摘掉注解的类不应被判为 Bean")

        annotated = "package x;\n@Slf4j\n@Component\npublic class ErpAdTools {\n}\n"
        self.assertTrue(is_bean((Path("ErpAdTools.java"), annotated, "class")),
                        "带 @Component 的类必须被判为 Bean")

    def test_injection_regex_finds_plain_and_optional_forms(self):
        """自检：注入点的两种写法都要能抓到，否则检查面会悄悄变窄。"""
        sample = (
            "@Autowired\n    private ErpAdTools adTools;\n"
            "@Autowired(required = false)\n    private AdServiceClient adServiceClient;\n"
            "@Resource\n    private FooService fooService;\n"
        )
        found = injected_fields(sample)
        names = {f[2] for f in found}
        self.assertEqual(names, {"adTools", "adServiceClient", "fooService"})


if __name__ == "__main__":
    unittest.main()
