# -*- coding: utf-8 -*-
# 一次性发布脚本：读凭据 -> 建仓 -> commit -> push -> 校验。token 不写盘、不打印。
import ctypes
import json
import os
import subprocess
import sys
import urllib.request
from ctypes import wintypes

ROOT = r'C:\Users\Administrator\Desktop\reader'
OWNER = 'GrandMageSombre'
REPO = 'reader'
LOG = []
TOKEN = None


def log(s):
    LOG.append(s)
    print(s, flush=True)


# ---------- 1. 从 Windows 凭据管理器读 GitHub token ----------
class CREDENTIAL(ctypes.Structure):
    _fields_ = [("Flags", wintypes.DWORD),
                ("Type", wintypes.DWORD),
                ("TargetName", wintypes.LPWSTR),
                ("Comment", wintypes.LPWSTR),
                ("LastWritten", wintypes.FILETIME),
                ("CredentialBlobSize", wintypes.DWORD),
                ("CredentialBlob", ctypes.POINTER(wintypes.BYTE)),
                ("Persist", wintypes.DWORD),
                ("AttributeCount", wintypes.DWORD),
                ("Attributes", ctypes.c_void_p),
                ("TargetAlias", wintypes.LPWSTR),
                ("UserName", wintypes.LPWSTR)]


def read_token():
    adv = ctypes.WinDLL('advapi32', use_last_error=True)
    cred = ctypes.pointer(CREDENTIAL())
    ok = adv.CredReadW('git:https://github.com', 1, 0, ctypes.byref(cred))
    if not ok:
        return None, 'CredReadW failed err=%s' % ctypes.get_last_error()
    try:
        size = cred.contents.CredentialBlobSize
        buf = ctypes.string_at(cred.contents.CredentialBlob, size)
        user = cred.contents.UserName or ''
        cand = []
        if b'\x00' in buf:
            try:
                cand.append(buf.decode('utf-16-le').rstrip('\x00'))
            except Exception:
                pass
        cand.append(buf.decode('utf-8', 'replace'))
        for t in cand:
            t = t.strip()
            if t and '\x00' not in t and len(t) > 20:
                return t, 'user=' + user + ' len=%d' % len(t)
        return None, 'blob decode failed size=%d' % size
    finally:
        adv.CredFree(ctypes.cast(cred, ctypes.c_void_p))


TOKEN, info = read_token()
log('[cred] ' + ('OK %s' % info if TOKEN else info))

# ---------- 2. GitHub API：查库 / 建库 ----------
def api(method, path, payload=None):
    if TOKEN is None:
        raise SystemExit('no token')
    url = 'https://api.github.com' + path
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Authorization', 'Bearer ' + TOKEN)
    req.add_header('Accept', 'application/vnd.github+json')
    req.add_header('User-Agent', 'modu-reader-publish')
    if data:
        req.add_header('Content-Type', 'application/json')
    # 优先直连，失败走代理
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        r = opener.open(req, timeout=30)
    except urllib.error.HTTPError as e:
        # 404 等业务状态码原样返回，让调用方自己判断
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception:
        try:
            r = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as e2:
            return e2.code, e2.read().decode('utf-8', 'replace')
    body = r.read().decode('utf-8', 'replace')
    return r.status, body


status, body = api('GET', '/repos/%s/%s' % (OWNER, REPO))
repo_exists = status == 200
log('[repo] exists=%s (GET status=%s)' % (repo_exists, status))
if status == 404:
    st, body = api('POST', '/user/repos', {
        'name': REPO,
        'description': '墨读 MoDuReader——规则驱动的 Android 小说阅读器（Kotlin + Compose）',
        'homepage': '',
        'private': False,
        'has_issues': True,
        'auto_init': False,
    })
    log('[repo] create status=%s %s' % (st, json.loads(body).get('html_url', '') if st in (200, 201) else body[:200]))
    if st not in (200, 201):
        raise SystemExit('create repo failed')
elif status != 200:
    log('[repo] unexpected status, body=' + body[:300])
    raise SystemExit('repo check failed')
else:
    log('[repo] 已存在，直接提交推送：' + json.loads(body).get('html_url', ''))

# ---------- 3. .gitignore 补条目 ----------
gi_path = os.path.join(ROOT, '.gitignore')
gi = open(gi_path, encoding='utf-8').read()
changed = False
for extra in ['.kotlin/', 'releases/']:
    if extra not in gi:
        gi = gi.rstrip('\n') + '\n' + extra + '\n'
        changed = True
if changed:
    open(gi_path, 'w', encoding='utf-8').write(gi)
    log('[gitignore] added .kotlin/ releases/')

# ---------- 4. git init / add / commit ----------
env = dict(os.environ)
env['GIT_TERMINAL_PROMPT'] = '0'
env['GCM_INTERACTIVE'] = 'never'


def git(args, timeout=120):
    r = subprocess.run(['git'] + args, cwd=ROOT, capture_output=True, env=env,
                       timeout=timeout)
    so = (r.stdout or b'').decode('utf-8', 'replace').strip()
    se = (r.stderr or b'').decode('utf-8', 'replace').strip()
    return r.returncode, so, se


rc, so, se = git(['init', '-b', 'main'])
log('[init] rc=%s %s%s' % (rc, so[:120], se[:120]))
rc, so, se = git(['add', '-A'])
log('[add] rc=%s %s%s' % (rc, so[:200], se[:200]))
rc, so, se = git(['status', '--short'])
log('[status] files=%d' % len(so.splitlines()))

msg_path = os.path.join(ROOT, '.git', 'COMMIT_MSG_MODU.tmp')
msg = (
    'feat(app): v1.0.0 首个可发布版——规则驱动书源引擎 + 书城/书架/阅读器\n'
    '\n'
    '- 书源 JSON 规则引擎：分类/书单/详情/目录/正文全部 CSS 选择器驱动，换站点只改配置\n'
    '- 书城多书源切换、分类无限翻页、单源搜索；书架进度记忆与继续阅读\n'
    '- 阅读器：字号/行距/段距/字体/白米黄护眼夜间四配色/亮度/常亮，章节目录与进度条\n'
    '- 章节与正文 SQLite 缓存，支持强制重抓；内置公版示例书库\n'
    '- 首轮四视角审查 P1 七项修复，详见 docs/reviews/审查报告1.md\n'
)
open(msg_path, 'w', encoding='utf-8', newline='\n').write(msg)
rc, so, se = git(['-c', 'i18n.commitEncoding=utf-8', 'commit', '-F', msg_path])
log('[commit] rc=%s %s%s' % (rc, so[:200], se[:300]))
try:
    os.remove(msg_path)
except OSError:
    pass

rc, so, se = git(['rev-parse', 'HEAD'])
head = so.strip()
log('[head] %s' % head)
if rc != 0 or not head:
    raise SystemExit('commit failed')

# ---------- 5. remote + push（token 只进命令行，不写进 config） ----------
rc, so, se = git(['remote', 'remove', 'origin'])
rc, so, se = git(['remote', 'add', 'origin', 'https://github.com/%s/%s.git' % (OWNER, REPO)])
log('[remote] added origin')

push_url = 'https://%s:%s@github.com/%s/%s.git' % (OWNER, TOKEN, OWNER, REPO)
push_env = dict(env)
push_env['GIT_ASKPASS'] = 'echo'
r = subprocess.run(
    ['git', '-c', 'credential.helper=', 'push', push_url, 'main:main'],
    cwd=ROOT, capture_output=True, env=push_env, timeout=600)
so = (r.stdout or b'').decode('utf-8', 'replace')
se = (r.stderr or b'').decode('utf-8', 'replace')
# 输出里万一有 token，打码
so = so.replace(TOKEN, '***')
se = se.replace(TOKEN, '***')
log('[push] rc=%s\n  out=%s\n  err=%s' % (r.returncode, so[-400:], se[-600:]))

# ---------- 6. 远端引用落到本地（本机 git 写 refs/remotes 静默失败的老坑） ----------
refs_dir = os.path.join(ROOT, '.git', 'refs', 'remotes', 'origin')
os.makedirs(refs_dir, exist_ok=True)
with open(os.path.join(refs_dir, 'main'), 'w', encoding='utf-8') as f:
    f.write(head + '\n')
log('[refs] wrote refs/remotes/origin/main')

# ---------- 7. API 校验远端 ----------
st, body = api('GET', '/repos/%s/%s/commits?per_page=1' % (OWNER, REPO))
if st == 200:
    remote_sha = json.loads(body)[0]['sha']
    log('[verify] remote head=%s local=%s match=%s' % (remote_sha, head, remote_sha == head))
else:
    log('[verify] GET commits status=%s' % st)

with open(os.path.join(ROOT, '_publish.log'), 'w', encoding='utf-8') as f:
    f.write('\n'.join(LOG))
print('PUBLISH_DONE rc_push=%s' % r.returncode)
