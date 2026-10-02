"""Completion audit: find backend endpoints with no frontend caller.

Coarse on purpose: it maps controller paths against the strings used in
amz-frontend/src/api/*.ts. A hit means "some frontend code names this path",
not "the button works". Anything reported as missing must be checked by hand.
"""
import io
import re
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
fe_api = root / 'amz-frontend' / 'src' / 'api'

CLASS_MAP = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"')
METHOD_MAP = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"?')

frontend_text = "\n".join(
    io.open(p, encoding='utf-8', errors='replace').read() for p in fe_api.glob('*.ts')
)


def build_matcher(full_path: str) -> str:
    """把 /a/{id}/b 变成可以匹配前端模板串的正则：参数段允许 ${...} 或任意非斜杠内容。"""
    out = []
    for seg in full_path.split('/'):
        if not seg:
            continue
        if seg.startswith('{') and seg.endswith('}'):
            out.append(r"(?:\$\{[^}]*\}|[^/`'\"]+)")
        else:
            out.append(re.escape(seg))
    return '/'.join(out)


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


def collect_feign_paths() -> set:
    """其他模块的 Feign 客户端声明过的路径 = 存在服务间调用方，不该由浏览器负责。"""
    found = set()
    for client in sorted(root.glob('amz-service/*/src/main/java/com/amz/client/**/*.java')):
        text = io.open(client, encoding='utf-8', errors='replace').read()
        for _, p in METHOD_MAP.findall(text):
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
for controller in sorted(root.glob('amz-service/*/src/main/java/com/amz/controller/*.java')):
    text = io.open(controller, encoding='utf-8', errors='replace').read()
    cls = CLASS_MAP.search(text)
    prefix = cls.group(1) if cls else ''
    methods = list(METHOD_MAP.finditer(text))
    if not methods:
        continue
    missing = []
    for m in methods:
        verb, path = m.group(1), m.group(2)
        full = (prefix + path) if path.startswith('/') or path == '' else prefix + '/' + path
        full = full.rstrip('/')
        if full == prefix.rstrip('/') and not path:
            continue
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
        block = annotation_block(text, m.start())
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
print('matched_via_gateway_alias=%d' % len(alias_hits))
for path, alias in alias_hits:
    print('   %s  <- 前端写作 %s' % (
        path.encode('ascii', 'backslashreplace').decode(),
        alias.encode('ascii', 'backslashreplace').decode()))
print('')
print('==== 待接候选（既没有 @InternalServiceAccess，也没有任何 Feign 客户端指向它）====')
for module, name, ep, _ in candidates:
    print('  %-14s %-34s %s' % (module, name, ep.encode('ascii', 'backslashreplace').decode()))
