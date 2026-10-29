import os
import subprocess

root = r'C:\Users\Administrator\Desktop\reader'
out = []

print('.git exists:', os.path.isdir(os.path.join(root, '.git')))

def run(args, cwd=root, inp=None):
    env = dict(os.environ)
    env['GCM_INTERACTIVE'] = 'never'
    env['GIT_TERMINAL_PROMPT'] = '0'
    r = subprocess.run(args, cwd=cwd, capture_output=True, input=inp,
                       text=True, encoding='utf-8', errors='replace', env=env, timeout=60)
    return r.returncode, (r.stdout or '').strip(), (r.stderr or '').strip()

rc, so, se = run(['git', 'config', '--global', 'user.name'])
out.append('user.name rc=%s %s' % (rc, so or se))
rc, so, se = run(['git', 'config', '--global', 'user.email'])
out.append('user.email rc=%s %s' % (rc, so or se))

try:
    rc, so, se = run(['git', 'credential', 'fill'], inp='protocol=https\nhost=github.com\n\n')
    tok = ''
    user = ''
    for line in so.splitlines():
        if line.startswith('password='):
            tok = line.split('=', 1)[1]
        if line.startswith('username='):
            user = line.split('=', 1)[1]
    out.append('credential rc=%s username=%s token=%s' % (rc, user, (tok[:6] + '...') if tok else 'NONE'))
    ok = bool(tok)
except subprocess.TimeoutExpired:
    out.append('credential TIMEOUT')
    ok = False

with open(os.path.join(root, '_git_env.txt'), 'w', encoding='utf-8') as f:
    f.write('\n'.join(out))
    f.write('\nTOKEN_OK=' + str(ok))
print('token ok:', ok)
