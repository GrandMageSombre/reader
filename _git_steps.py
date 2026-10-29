import os
import subprocess

root = r'C:\Users\Administrator\Desktop\reader'
log = open(os.path.join(root, '_git_steps.log'), 'w', encoding='utf-8')

def logline(s):
    log.write(s + '\n')
    log.flush()

def step(name, args, inp=None, timeout=25):
    env = dict(os.environ)
    env['GCM_INTERACTIVE'] = 'never'
    env['GIT_TERMINAL_PROMPT'] = '0'
    env['GCM_GUI_PROMPT'] = 'never'
    try:
        r = subprocess.run(args, cwd=root, capture_output=True, input=inp,
                           text=True, encoding='utf-8', errors='replace',
                           env=env, timeout=timeout)
        logline('[%s] rc=%s out=%r err=%r' % (name, r.returncode, (r.stdout or '')[:300], (r.stderr or '')[:300]))
        return r
    except subprocess.TimeoutExpired:
        logline('[%s] TIMEOUT after %ss' % (name, timeout))
        return None

logline('start, .git exists=%s' % os.path.isdir(os.path.join(root, '.git')))
step('git-version', ['git', '--version'])
step('global-name', ['git', 'config', '--global', 'user.name'])
step('global-email', ['git', 'config', '--global', 'user.email'])
step('credential', ['git', 'credential', 'fill'],
     inp='protocol=https\nhost=github.com\n\n', timeout=40)
logline('end')
log.close()
print('done')
