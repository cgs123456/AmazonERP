"""Endpoint-role audit: 前端 view 调用的每个 API，后端到底锁没锁角色。

这把尺是为 §S20.7 那类事故准备的。事故的起因两次都一样：**凭邻近代码的印象
推断**「这个方法大概也有 @RequireRole」，而不是去读原文。一次是 §S19.6 假设
四页后端都锁 OPERATOR/ADMIN（实际只有两页锁）；一次是 §S20.7 声称
`GET /finance/discrepancy/{id}` 有 `@RequireRole`（实际只有 `@ShopScoped`）——
后一处是看着同文件 `dismiss` 方法的注解写出来的。

这类错误没有测试能发现：门控加少了是「点了报错」，加多了是「入口直接消失」，
后者连 bug 报告都不会有。所以只能靠**把事实摆出来**让人读，而不是靠断言。

口径（从窄，沿用同目录三把尺踩出来的方法论）：
- 前端 API 取 `src/api/*.ts` 里 `export const NAME = (...) => request.<verb>(url)`。
  模板字符串里的 `${x}` 与后端 `{x}` 归一化后比对，不做语义猜测。
- 后端取每个 `@RequestMapping` 前缀 + `@Get/Post/Put/DeleteMapping`，
  判断该方法**前方紧邻的注解块**里有没有 `@RequireRole`。类级注解也识别。
- 只回答「锁没锁」这一个二值问题。不判断前端该不该加门控——那是产品决策
  （见 HANDOFF §S20.6 待办 ①），本尺不替它决定。
- **已知的伪未匹配**：`vite.config.ts` 的 `rewriteProxyPath` 会在代理层改前缀
  （如 `/api/connectors` → 后端 `/spapi/connectors`），本尺按原始字符串比对，
  这类会报「未匹配到后端端点」。当前残留的 4 条（`runAgentEval`、`listOutbox`、
  `listRateLimits`、`replayOutbox`）全是这一类，**不是扫描器缺陷也不是真缺口**；
  真要消掉得把 rewrite 规则也解析进来，收益不抵复杂度，故保留披露。
- 分母必须打印。**未匹配一律披露不静默跳过**：解析失败会伪装成 0 findings，
  那正是 §S20.7 的教训。第一版产出 385 条噪声（把 `ref`/`computed` 这些
  Vue 导入也当 API 收进来），逐条收窄到 4 条——收窄过程本身修掉了三个真实
  解析缺陷：裸 `@PostMapping`、`@RequestMapping` 与 `public class` 之间的
  `@Slf4j`、以及换行的方法签名/URL。

用法：
    python tools/schema/endpoint_role_audit.py                 # 全量概览
    python tools/schema/endpoint_role_audit.py --table Finance # 单个 view 的明细表

明细表是这把尺的主要产出：改任何「读/写共用出口」之前先跑它。
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FRONTEND = ROOT / 'amz-frontend'
SERVICE = ROOT / 'amz-service'


def norm(path):
    """`${id}` / `{id}` 都归一成 `{}`：拼路径占位符，不做语义猜测。"""
    return re.sub(r'\{[^}]*\}', '{}', re.sub(r'\$\{[^}]*\}', '{}', path))


def load_frontend_api():
    """name -> (verb, url)。同名取首次定义。"""
    api = {}
    for f in (FRONTEND / 'src' / 'api').glob('*.ts'):
        src = f.read_text(encoding='utf-8')
        # URL 可能换行（如 procurement.ts 的 listReceiptShortages：`request.get<...>(` 后
        # 模板字符串另起一行）。只吃紧邻的空白与续行，不跨语句。
        for m in re.finditer(
            r'export const (\w+)\s*=\s*\([^)]*\)\s*=>\s*request\.(get|post|put|delete)'
            r'[^(]*\(\s*(?:\r?\n\s*)?[`"\']([^`"\']+)', src, re.S):
            api.setdefault(m.group(1), (m.group(2), m.group(3)))
    return api


def load_backend():
    """(verb, normalized path) -> (has_require_role, source_file, method_name)"""
    endpoints = {}
    for jf in SERVICE.rglob('*.java'):
        try:
            src = jf.read_text(encoding='utf-8')
        except (UnicodeDecodeError, OSError):
            continue
        # `@RequestMapping` 与 `public class` 之间可能有 @Slf4j 等其他注解
        # （ProcurementController 就是这样，第一版因此整类漏掉、产出伪「未匹配」）。
        cm = re.search(r'@RequestMapping\(\s*"([^"]+)"\s*\)[\s\S]{0,200}?public\s+class', src)
        if not cm:
            continue
        prefix = cm.group(1)
        # 方法签名可能换行（参数另起一行是常见写法，且尖括号里嵌泛型时
        # `[^\n]*` 匹配不到）。只要求返回类型是 Result<，方法名允许在下一行。
        for m in re.finditer(
            r'((?:^\s*@\w+(?:\([^)]*\))?\s*\n)+)(\s*public\s+Result<[\s\S]{0,200}?\s(\w+)\s*\()',
            src, re.M):
            ann = m.group(1)
            # `@PostMapping` 无参数也是合法写法，路径取类级 @RequestMapping。
            # 例如 StocktakeController 的 POST /logistics/stocktake 就是 `@PostMapping` 裸注解，
            # 第一版漏了它，产出 14 条伪「未匹配」。
            mp = re.search(r'@(Get|Post|Put|Delete|Patch)Mapping\s*(?:\(\s*"([^"]*)"\s*\)|\(\s*\))?', ann)
            if not mp:
                continue
            verb = mp.group(1).lower()
            path = prefix + (mp.group(2) or '')
            endpoints.setdefault((verb, norm(path)),
                                 ('@RequireRole' in ann, jf.name, m.group(3)))
    return endpoints


def scan_view(path, known_api=None):
    """返回该 view 用到的 API 名 -> 出现次数。

    刻意只收「能解析成 api/*.ts 里定义的函数」的名字。第一版把 import 里的
    Vue 响应式 API（`ref`/`computed`/`onMounted`）和常量（`WRITE_LOCKED_HINT`）
    也收进来，产出 385 条噪声——**噪声正是这类尺失效的方式**：真正的未匹配
    会淹在里面看不见。所以未匹配要在过滤后才统计，且只留真的像 API 的。

    <h2>命名空间导入（`import * as X`）必须解析出来</h2>
    此前这里硬编码了三种前缀（`ext`/`api`/`warehouseApi`），于是
    `import * as cust from '@/api/customer'` 这种写法**整个文件都扫不到**——
    CustomerService.vue 一度报「前端 API 命中 0 个」，看起来像「这页没有写操作」，
    实际它是 P0 要加锁的两个页面之一。<b>扫描器漏报比误报危险得多</b>：
    误报会被人肉忽略，漏报则伪装成「已经查过了，没问题」。
    所以前缀改为从文件里按其真实别名解析，不再维护白名单。
    """
    src = path.read_text(encoding='utf-8')
    script = src[src.find('<script setup'):] if '<script setup' in src else src
    known_api = known_api or set()
    names = {}

    def add(n):
        if n in known_api:
            names[n] = names.get(n, 0) + 1

    # 1. 命名空间导入：import * as <alias> from '@/api/...'
    ns_aliases = set()
    for m in re.finditer(r"import\s*\*\s*as\s+(\w+)\s+from\s*['\"][^'\"]*api[^'\"]*['\"]",
                         script):
        ns_aliases.add(m.group(1))
    for alias in ns_aliases:
        for m in re.finditer(r'\b' + re.escape(alias) + r'\.(\w+)\b', script):
            add(m.group(1))
    # 2. 具名导入：import { x } from '@/api/...'（同样接受 `as` 别名）
    for m in re.finditer(r"import\s*\{([^}]+)\}\s*from\s*['\"][^'\"]*api[^'\"]*['\"]",
                         script, re.S):
        for raw in m.group(1).split(','):
            add(raw.split(' as ')[-1].strip())
    return names


def resolve(api, backend, name):
    if name not in api:
        return None, '前端 api/ 未定义'
    verb, url = api[name]
    key = (verb, norm(url))
    if key not in backend:
        return None, '未匹配到后端端点'
    has_role, jf, method = backend[key]
    return (verb, url, has_role, jf, method), None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--table', metavar='VIEW',
                    help='只打印该 view 的明细表（文件名含此串即可）')
    args = ap.parse_args()

    api = load_frontend_api()
    backend = load_backend()
    print('前端 api 定义 %d 个；后端端点 %d 个' % (len(api), len(backend)))

    views = sorted((FRONTEND / 'src' / 'views').glob('*.vue'))
    if args.table:
        views = [v for v in views if args.table in v.name]
        if not views:
            print('没有匹配 %r 的 view' % args.table)
            return 1

    findings = []          # 未匹配：解析失败必须可见，不能静默跳过
    total_calls = 0
    resolved = 0
    locked = 0

    for v in views:
        names = scan_view(v, set(api))
        rows = []
        for name in sorted(names):
            r, why = resolve(api, backend, name)
            if r is None:
                findings.append((v.name, name, why))
                continue
            verb, url, has_role, jf, method = r
            total_calls += 1
            resolved += 1
            if has_role:
                locked += 1
            rows.append((name, verb, url, has_role, jf))
        if args.table:
            print('\n=== %s ===' % v.name)
            print('%-30s %-6s %-52s %s' % ('API', 'METHOD', 'URL', '后端角色要求'))
            print('-' * 116)
            for name, verb, url, has_role, jf in rows:
                print('%-30s %-6s %-52s %s  <- %s'
                      % (name, verb, url,
                         'OPERATOR/ADMIN' if has_role else '无（VIEWER 可过）', jf))
        elif rows:
            w = sum(1 for r in rows if r[3])
            print('  %-34s 已解析 %3d（锁 %3d / 未锁 %3d）'
                  % (v.name, len(rows), w, len(rows) - w))

    print('\n解析到 API 调用 %d 个，其中后端锁角色 %d 个' % (resolved, locked))
    if findings:
        print('disclosed unmatched=%d（非阻断，但必须可见：静默跳过会伪装成 0 findings）'
              % len(findings))
        for f, n, why in findings:
            print('   %-34s %-30s %s' % (f, n, why))
    print('endpoint-role gate: %d findings（未匹配只披露，本尺不判前端该不该锁）'
          % len(findings))
    return 0


if __name__ == '__main__':
    sys.exit(main())
