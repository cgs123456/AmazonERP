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


rows = []
for controller in sorted(root.glob('amz-service/*/src/main/java/com/amz/controller/*.java')):
    text = io.open(controller, encoding='utf-8', errors='replace').read()
    cls = CLASS_MAP.search(text)
    prefix = cls.group(1) if cls else ''
    methods = METHOD_MAP.findall(text)
    if not methods:
        continue
    missing = []
    for verb, path in methods:
        full = (prefix + path) if path.startswith('/') or path == '' else prefix + '/' + path
        full = full.rstrip('/')
        if full == prefix.rstrip('/') and not path:
            continue
        # 前端写的是模板串（`/search/search/${encodeURIComponent(key)}`），
        # 所以要把 {param} 与 ${...} 都当成通配段再比，否则每个带路径参数的端点都会误报成没人用。
        # 这一版初稿就是用字面串比较，把已经接好的 /search/search/{key} 报成了缺口。
        pattern = re.compile(build_matcher(full))
        if pattern.search(frontend_text):
            continue
        missing.append('%s %s' % (verb.upper(), full))
    rows.append((controller.parent.parent.name, controller.name, len(methods), missing))

print('%-22s %-34s %5s %s' % ('module', 'controller', 'eps', 'endpoints not named by amz-frontend/src/api'))
total_missing = 0
for module, name, n, missing in rows:
    mark = '' if not missing else '  <-- %d missing' % len(missing)
    print('%-22s %-34s %5d%s' % (module, name, n, mark))
    for m in missing:
        print('        ' + m.encode('ascii', 'backslashreplace').decode())
        total_missing += 1
print('controllers=%d endpoints_without_a_frontend_name=%d' % (len(rows), total_missing))
