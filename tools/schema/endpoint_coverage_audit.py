"""Completion audit: find backend endpoints with no frontend caller.

Coarse on purpose: it maps controller paths against the strings used in
amz-frontend/src/api/*.ts. A hit means "some frontend code names this path",
not "the button works". Anything reported as missing must be checked by hand.
Comments are stripped first, so a path that only appears in an explanatory
comment is still reported as missing (that is the point).
"""
import io
import re
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
fe_api = root / 'amz-frontend' / 'src' / 'api'

CLASS_MAP = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"')
# 方法级注解先按行抓，再从括号里解析路径：
# 上一版用「必须有 ( 和字符串」的正则，直接把 15 个 `@PostMapping`（无参、路径就是类前缀）
# 整个漏掉了 —— 分母本身就少算，被漏掉的还偏偏是数据写入端点。
MAPPING_LINE = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping\b[ \t]*([^\n]*)')
PATH_LITERAL = re.compile(r'(?:value|path)\s*=\s*"([^"]*)"|"([^"]*)"')

# 注释里写出的路径不算「前端调用过」。这一条是踩出来的：opsAlerts.ts 的头注里
# 列着三个「刻意不接」的扫描端点（为了说明为什么不接），整文件文本匹配因此把它们
# 判成了已覆盖，OpsController 显示 0 条缺口——而页面上根本没有这三个按钮。
# `(?<!:)` 是为了不吃掉 https:// 这类串里的双斜杠。
LINE_COMMENT = re.compile(r'(?<!:)//[^\n]*')
BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)
# 两种注释一次性按位置扫掉。分开两次 sub 会串味：先剥块注释时，行注释里出现的
# `api/*.ts` 会被当成块注释的起点，一路吞到文件里下一个 `*/`，把真正的调用代码删没
# ——agentMemory.ts 就是这么被误判成「未接入」的。交替匹配则从左到右消费，
# 行注释整行先没了，块注释内部再含 `//` 也不会提前收尾。
COMMENTS = re.compile(r'(?<!:)//[^\n]*|/\*.*?\*/', re.S)


def strip_comments(text: str) -> str:
    return COMMENTS.sub('', text)

frontend_text = "\n".join(
    strip_comments(io.open(p, encoding='utf-8', errors='replace').read())
    for p in fe_api.glob('*.ts')
)


def build_matcher(full_path: str) -> str:
    """把 /a/{id}/b 变成可以匹配前端模板串的正则：参数段允许 ${...} 或任意非斜杠内容。

    结尾必须钉住：上一版没有右边界，`/ai/chat` 会被 api/ai.ts 里的 `/ai/chat-stream`
    命中，于是 POST /ai/chat 这个前端根本没调的端点被算成「已接入」——
    少报缺口比多报缺口更危险，因为它会让清点结果看起来已经收敛。
    允许的后继字符里没有 `-` 与字母数字，斜杠仍然允许（子路径照常匹配）。
    """
    out = []
    for seg in full_path.split('/'):
        if not seg:
            continue
        if seg.startswith('{') and seg.endswith('}'):
            out.append(r"(?:\$\{[^}]*\}|[^/`'\"]+)")
        else:
            out.append(re.escape(seg))
    return '/'.join(out) + r'(?![\w-])'


if '--self-test' in sys.argv:
    # 三条都是踩过的坑，钉在这里而不是只写注释：任何一条断了，清点结果就不可信。
    cases = []

    def check(name, got, want):
        cases.append((name, got == want, got, want))

    line_only = "// 见 api/*.ts 与其余约定\nrequest.get(`/ai/agent/memory/preference/${userId}`)\n"
    check('行注释里的 api/*.ts 不得吞掉下一行调用代码',
          bool(re.compile(build_matcher('/ai/agent/memory/preference/{userId}'))
              .search(strip_comments(line_only))), True)

    block_only = "/** 刻意不接：POST /ops/review/scan、POST /ops/rank/scan */\n"
    check('块注释里列出的路径不算调用',
          bool(re.compile(build_matcher('/ops/review/scan')).search(strip_comments(block_only))), False)

    near_miss = "request.get(`/ai/chat-stream`)\n"
    check('/ai/chat 不得被 /ai/chat-stream 命中',
          bool(re.compile(build_matcher('/ai/chat')).search(near_miss)), False)
    check('/ai/chat-stream 本身仍要命中',
          bool(re.compile(build_matcher('/ai/chat-stream')).search(near_miss)), True)

    nested = "request.get(`/ad/report/${shopId}`)\n"
    check('子路径式命中仍成立（斜杠后继续写路径不算断点）',
          bool(re.compile(build_matcher('/ad/report/{shopId}')).search(nested)), True)

    failed = [c for c in cases if not c[1]]
    for name, ok_flag, got, want in cases:
        print('%-56s %s (got=%s want=%s)' % (
            name.encode('ascii', 'replace').decode(), 'PASS' if ok_flag else 'FAIL', got, want))
    print('SELFTEST %d/%d passed' % (len(cases) - len(failed), len(cases)))
    sys.exit(1 if failed else 0)


# 网关/vite 代理里确实存在的别名：后端前缀 -> 前端书写前缀。
GATEWAY_ALIASES = {
    '/spapi/connectors': '/connectors',
    '/spapi/preflight': '/preflight',
    '/spapi/credentials': '/credentials',
}


def to_frontend_alias(full_path: str) -> str:
    for backend_prefix, frontend_prefix in GATEWAY_ALIASES.items():
        if full_path == backend_prefix or full_path.startswith(backend_prefix + '/'):
            return frontend_prefix + full_path[len(backend_prefix):]
    return ''


def shape(path: str) -> str:
    """把 /a/{id}/b 与 /a/${x}/b 都归一成 /a/{}/b，用于跨服务比对同一条路径。"""
    segs = ['{}' if s.startswith('{') or s.startswith('$') else s for s in path.split('/') if s]
    return '/' + '/'.join(segs)


def parse_mappings(text: str):
    """把方法级 mapping 注解解析成 (verb, path, anchor, raw)。

    path == '' 表示无参注解（`@PostMapping`），它的路径就是类前缀；
    path is None 表示这行有括号但没解析出字符串字面量（consumes/produces/params 之类），
    调用方必须把它打印出来——工具的失败模式只能是「少算并说明」，不能是静默少一条。
    """
    out = []
    for m in MAPPING_LINE.finditer(text):
        verb, rest = m.group(1), m.group(2).strip()
        raw = m.group(0).strip()
        if not rest:
            out.append((verb, '', m.start(), raw))
            continue
        if not rest.startswith('('):
            out.append((verb, None, m.start(), raw))
            continue
        literal = PATH_LITERAL.search(rest)
        path = ((literal.group(1) or literal.group(2)) if literal else None)
        out.append((verb, path, m.start(), raw))
    return out


def collect_feign_paths() -> set:
    """其他模块的 Feign 客户端声明过的路径 = 存在服务间调用方，不该由浏览器负责。"""
    found = set()
    for client in sorted(root.glob('amz-service/*/src/main/java/com/amz/client/**/*.java')):
        text = io.open(client, encoding='utf-8', errors='replace').read()
        for _, p, _, _ in parse_mappings(text):
            if p:
                found.add(shape(p if p.startswith('/') else '/' + p))
    return found


def annotation_block(text: str, anchor: int) -> str:
    """取一个 mapping 注解所属的方法注解块：往前找到上一个 } 或 ; 为止。"""
    start = max(text.rfind('}', 0, anchor), text.rfind(';', 0, anchor)) + 1
    return text[start:anchor]


FEIGN_PATHS = collect_feign_paths()

rows = []
alias_hits = []
unparsed = []
for controller in sorted(root.glob('amz-service/*/src/main/java/com/amz/controller/*.java')):
    text = io.open(controller, encoding='utf-8', errors='replace').read()
    cls = CLASS_MAP.search(text)
    prefix = cls.group(1) if cls else ''
    methods = parse_mappings(text)
    if not methods:
        continue
    missing = []
    for verb, path, anchor, raw in methods:
        if path is None:
            unparsed.append((controller.name, raw))
            continue
        full = (prefix + path) if path.startswith('/') or path == '' else prefix + '/' + path
        full = full.rstrip('/') or '/'
        # 前端写的是模板串（`/search/search/${encodeURIComponent(key)}`），
        # 所以要把 {param} 与 ${...} 都当成通配段再比，否则每个带路径参数的端点都会误报成没人用。
        # 这一版初稿就是用字面串比较，把已经接好的 /search/search/{key} 报成了缺口。
        if re.compile(build_matcher(full)).search(frontend_text):
            continue
        # 网关别名：前端写 /connectors/outbox，后端注册的是 /spapi/connectors/outbox。
        # 只认这张显式表。曾经的「去掉第一段再比」会把 /ad/report/{shopId} 误当成
        # /report/{shopId}（那是报表服务的同名路径），等于工具自己造绿灯。
        alias = to_frontend_alias(full)
        if alias and re.compile(build_matcher(alias)).search(frontend_text):
            alias_hits.append((full, alias))
            continue
        block = annotation_block(text, anchor)
        tags = []
        if '@InternalServiceAccess' in block:
            tags.append('internal-access')
        if shape(full) in FEIGN_PATHS:
            tags.append('has-feign-caller')
        missing.append(('%s %s' % (verb.upper(), full), tags or ['user-facing-candidate']))
    rows.append((controller.parent.parent.name, controller.name, len(methods), missing))

candidates = [(mod, name, ep, tags) for mod, name, _, missing in rows
              for ep, tags in missing if tags == ['user-facing-candidate']]

print('%-22s %-34s %5s %s' % ('module', 'controller', 'eps', 'endpoints not named by amz-frontend/src/api'))
total_missing = 0
for module, name, n, missing in rows:
    mark = '' if not missing else '  <-- %d missing' % len(missing)
    print('%-22s %-34s %5d%s' % (module, name, n, mark))
    for ep, tags in missing:
        print('        [%s] %s' % (','.join(tags), ep.encode('ascii', 'backslashreplace').decode()))
        total_missing += 1
print('controllers=%d endpoints_without_a_frontend_name=%d user_facing_candidates=%d'
      % (len(rows), total_missing, len(candidates)))
print('parsed_method_annotations=%d unparsed=%d'
      % (sum(n for _, _, n, _ in rows), len(unparsed)))
print('matched_via_gateway_alias=%d' % len(alias_hits))
for path, alias in alias_hits:
    print('   %s  <- 前端写作 %s' % (
        path.encode('ascii', 'backslashreplace').decode(),
        alias.encode('ascii', 'backslashreplace').decode()))
print('')
print('==== 待接候选（既没有 @InternalServiceAccess，也没有任何 Feign 客户端指向它）====')
for module, name, ep, _ in candidates:
    print('  %-14s %-34s %s' % (module, name, ep.encode('ascii', 'backslashreplace').decode()))
if unparsed:
    print('')
    print('==== 没解析出路径的注解（需要人工确认，不能当成「不存在」）====')
    for name, raw in unparsed:
        print('  %-34s %s' % (name, raw.encode('ascii', 'backslashreplace').decode()))
