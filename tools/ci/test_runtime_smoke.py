"""runtime_smoke 判定逻辑的单测。

这里的价值不在于覆盖率，而在于证明这道闸**会咬人**：
把真实的启动崩溃日志喂进去必须判失败，把健康日志喂进去必须判通过。
"""

from __future__ import annotations

import unittest

from tools.ci.runtime_smoke import (
    FATAL_SIGNATURES,
    ISOLATED_ENV,
    TRACE_SLOT_MARKER,
    build_command,
    classify,
)

# 2026-09-30 实测到的故障输出节选（HEAD 为 f1dfd1d 时 16 个服务全部起不来）
P0_LOGBACK_CRASH = """
11:21:53,442 |-WARN in ch.qos.logback.core.joran.action.ConversionRuleAction - [converterClass] attribute is deprecated
11:21:53,616 |-ERROR in ch.qos.logback.core.pattern.color.ConverterSupplierByClassName@452e26d0 - Failed to instantiate converter class [org.apache.skywalking.apm.toolkit.log.logback.v1.x.TraceIdConverter] for conversion word [traceId]
java.lang.IllegalStateException: Logback configuration error detected:
ERROR in ch.qos.logback.core.pattern.color.ConverterSupplierByClassName - Failed to instantiate converter class
Caused by: java.lang.ClassNotFoundException: org.apache.skywalking.apm.toolkit.log.logback.v1.x.TraceIdConverter
"""

HEALTHY_TEXT_LOG = """
2026-09-30 16:01:33.017 [main] [TID: N/A] INFO  com.amz.AmzNettyApplication - Starting AmzNettyApplication v1.0-SNAPSHOT
2026-09-30 16:01:33.101 [main] [TID: N/A] INFO  o.s.b.w.e.t.TomcatWebServer - Tomcat started on port 18901 (http)
2026-09-30 16:01:43.115 [main] [TID: N/A] INFO  com.amz.AmzNettyApplication - Started AmzNettyApplication in 10.098 seconds (process running for 10.854)
"""

HEALTHY_AGENT_LOG = """
2026-09-30 16:20:01.001 [http-nio-8095-exec-1] [TID:1f16f52d5e154eec90de89e3510cc05b.70.17907477202980001] INFO  c.a.i.BaseAuthInterceptor - 鉴权通过
2026-09-30 16:20:02.002 [main] INFO  com.amz.AmzServiceUserApplication - Started AmzServiceUserApplication in 50.219 seconds
"""

# 只缺 traceId 槽位、其余都正常的日志：证明槽位检查不是搭车断言
NO_TRACE_SLOT_LOG = HEALTHY_TEXT_LOG.replace("[TID: N/A] ", "[] ")


class ClassifyTest(unittest.TestCase):

    def test_real_p0_crash_log_is_fail(self) -> None:
        verdict = classify(P0_LOGBACK_CRASH)
        self.assertFalse(verdict.passed, "真实的启动崩溃日志必须判失败，否则这道闸没有意义")
        self.assertTrue(any("logback-configuration-error" in r for r in verdict.reasons), verdict.reasons)
        self.assertTrue(any("converter-instantiate-failure" in r for r in verdict.reasons), verdict.reasons)

    def test_every_fatal_signature_is_caught(self) -> None:
        for name, needle in FATAL_SIGNATURES:
            with self.subTest(signature=name):
                verdict = classify(needle + "\n")
                self.assertFalse(verdict.passed)
                self.assertTrue(any(r.startswith(name) for r in verdict.reasons), verdict.reasons)

    def test_healthy_text_log_is_pass(self) -> None:
        verdict = classify(HEALTHY_TEXT_LOG)
        self.assertTrue(verdict.passed, verdict.reasons)

    def test_healthy_agent_log_is_pass(self) -> None:
        verdict = classify(HEALTHY_AGENT_LOG)
        self.assertTrue(verdict.passed, verdict.reasons)

    def test_missing_started_marker_is_fail(self) -> None:
        verdict = classify("2026-09-30 16:01:33 [main] [TID: N/A] INFO  o.s.b.SpringApplication - Starting up\n")
        self.assertFalse(verdict.passed)
        self.assertTrue(any("启动成功标记" in r for r in verdict.reasons), verdict.reasons)

    def test_empty_trace_slot_marker_is_fail(self) -> None:
        """转换词坏掉时 %traceId 渲染为空方括号——槽位检查专门抓这个。"""
        verdict = classify(NO_TRACE_SLOT_LOG)
        self.assertFalse(verdict.passed)
        self.assertTrue(any(TRACE_SLOT_MARKER in r for r in verdict.reasons), verdict.reasons)
        # 明确关掉该项时不应误报（模块刻意不含该 pattern 的情况）
        self.assertTrue(classify(NO_TRACE_SLOT_LOG, expect_trace_slot=False).passed)


class IsolationTest(unittest.TestCase):

    def test_smoke_never_reaches_developer_middleware(self) -> None:
        """冒烟必须与自己机器上在跑的栈无关，否则本地绿 CI 红（或反之）不可信。"""
        self.assertEqual("127.0.0.1", ISOLATED_ENV["MYSQL_HOST"])
        self.assertEqual("127.0.0.1", ISOLATED_ENV["REDIS_HOST"])
        self.assertEqual("127.0.0.1", ISOLATED_ENV["RABBITMQ_HOST"])
        for key in ("MYSQL_PORT", "REDIS_PORT", "RABBITMQ_PORT"):
            self.assertEqual("1", ISOLATED_ENV[key], f"{key} 应指向已关闭端口")
        self.assertTrue(ISOLATED_ENV["NACOS_ADDR"].endswith(":1"))

    def test_command_disables_registry_and_config(self) -> None:
        cmd = build_command("x.jar", "mock", 18899, [])
        joined = " ".join(cmd)
        self.assertIn("--spring.cloud.nacos.discovery.enabled=false", joined)
        self.assertIn("--spring.cloud.nacos.discovery.register-enabled=false", joined)
        self.assertIn("--spring.profiles.active=mock", joined)
        self.assertIn("--server.port=18899", joined)


class MissingJarTest(unittest.TestCase):

    def test_run_smoke_fails_fast_on_absent_jar(self) -> None:
        from tools.ci.runtime_smoke import run_smoke
        verdict, _ = run_smoke("definitely-not-here.jar")
        self.assertFalse(verdict.passed)
        self.assertTrue(any("jar 不存在" in r for r in verdict.reasons), verdict.reasons)


if __name__ == "__main__":
    unittest.main()
