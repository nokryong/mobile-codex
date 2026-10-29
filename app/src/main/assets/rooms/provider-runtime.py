#!/usr/bin/env python3
"""Run official CLIs in Mobile Codex's private Linux guest, never external Termux.

No project directory is mounted here. All project/phone access goes through
mobile-mcp.py and the Android host's per-turn scope and approval checks.
"""
import argparse
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import time

LIMIT = 2 * 1024 * 1024
SAFE = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$')

def emit(**value):
    print(json.dumps(value, ensure_ascii=False), flush=True)

def write_json(file, value):
    file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temp = file.with_suffix('.tmp')
    temp.write_text(json.dumps(value), encoding='utf-8')
    temp.chmod(0o600)
    temp.replace(file)

def environment(provider):
    home = Path('/root/mobile-codex') / provider
    home.mkdir(parents=True, exist_ok=True, mode=0o700)
    env = {**os.environ, 'HOME': str(home), 'PATH': str(home / '.local/bin') + ':/usr/local/bin:/usr/bin:/bin',
           'TERM': 'xterm-256color', 'LANG': 'C.UTF-8', 'NO_COLOR': '1'}
    # Ask the CLI for its documented manual browser flow; no authentication
    # credentials are fabricated or copied from a desktop account.
    env['BROWSER'] = '/workspace/open-browser'
    env['SSH_CONNECTION'] = '127.0.0.1 1 127.0.0.1 1'
    Path('/workspace/open-browser').write_text('#!/bin/sh\nprintf "\\n%s\\n" "$1"\n', encoding='utf-8')
    Path('/workspace/open-browser').chmod(0o700)
    return env

def capture(argv, env, timeout=20):
    try:
        p = subprocess.run(argv, env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=timeout)
        return p.returncode, p.stdout[:LIMIT], p.stderr[:LIMIT]
    except subprocess.TimeoutExpired:
        return 124, '', '시간 초과'

def probe(provider, binary, env):
    if not Path(binary).is_file():
        return {'state': 'missing', 'auth': 'signed_out', 'reason': 'CLI 설치를 눌러 앱 내부 Linux에 설치해 주세요.', 'models': []}
    code, version, error = capture([binary, '--version'], env)
    if code:
        return {'state': 'unavailable', 'auth': 'unknown', 'reason': 'CLI 실행 실패. Linux 라이브러리와 설치 상태를 확인해 주세요.', 'models': []}
    code, help_out, help_err = capture([binary, '--help'], env)
    help_text = help_out + '\n' + help_err  # agy prints its usage to stderr
    required = ['--input-format', '--output-format', '--resume' if provider == 'claude' else '--conversation']
    if any(flag not in help_text for flag in required):
        return {'state': 'unsupported', 'auth': 'unknown', 'version': version.strip(), 'reason': '세션 스트리밍을 지원하는 CLI로 업데이트해 주세요.', 'models': []}
    models, usage = [], []
    if provider == 'claude':
        code, auth, _ = capture([binary, 'auth', 'status'], env)
        try:
            authenticated = code == 0 and json.loads(auth).get('loggedIn') is True
        except ValueError:
            authenticated = False
        # Aliases are read from the installed CLI, never a baked-in model list.
        section = re.search(r'--model\b(.*?)(?=\n\s+--|\Z)', help_text, re.S)
        aliases = re.findall(r'[\'\"]([a-z][a-z0-9._:/-]+)[\'\"]', section.group(1) if section else '')
        models = [{'id': m, 'label': m, 'efforts': []} for m in dict.fromkeys(aliases) if SAFE.fullmatch(m)]
        # Effort is intentionally disabled until this exact model advertises
        # supportedEffortLevels through the SDK initialize response.
        if authenticated:
            models = claude_models(binary, env)
            # /usage is a local command: it reports plan limits without sending a prompt.
            code, report, _ = capture([binary, '-p', '/usage'], env, timeout=60)
            usage = parse_claude_usage(report) if code == 0 else []
    else:
        code, listing, _ = capture([binary, 'models'], env)
        if code == 0:
            for line in listing.splitlines():
                match = re.match(r'^\s*([a-z][a-z0-9._-]+)\s+(.+)$', line)
                if match and SAFE.fullmatch(match[1]):
                    # AGY encodes reasoning in model slugs. Preserve the exact
                    # slug rather than offering unsupported per-model effort.
                    models.append({'id': match[1], 'label': match[2].strip(), 'efforts': []})
        authenticated, usage = agy_usage(binary, env)
    return {'state': 'ready' if authenticated else 'login_required', 'auth': 'signed_in' if authenticated else 'signed_out',
            'version': version.strip()[:150], 'models': models, 'transport': 'app-linux',
            'usage': usage if authenticated else [],
            'reason': '' if authenticated else '로그인을 눌러 CLI 계정 연결을 완료해 주세요.',
            'nativeAutoReview': supports_auto_review(provider, help_text),
            'nativeApproval': False, 'approval': 'mobile-mcp', 'resume': True, 'streaming': True}

def supports_auto_review(provider, help_text):
    section = re.search(r'--permission-mode\b(.*?)(?=\n\s+--|\Z)', help_text, re.S)
    return provider == 'claude' and bool(section and re.search(r'\bauto\b', section.group(1)))

def claude_models(binary, env):
    process = subprocess.Popen([binary, '-p', '--input-format', 'stream-json', '--output-format', 'stream-json',
        '--verbose', '--tools', '', '--strict-mcp-config', '--mcp-config', '{"mcpServers":{}}',
        '--setting-sources', '', '--settings', '{"disableAllHooks":true}'],
        env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    import queue
    import threading
    lines = queue.Queue()
    def read():
        for line in process.stdout:
            lines.put(line)
        lines.put(None)
    # A reader thread: select() cannot see lines already buffered by the text wrapper,
    # and the CLI may need several seconds to start in the Linux guest.
    threading.Thread(target=read, daemon=True).start()
    try:
        process.stdin.write(json.dumps({'type': 'control_request', 'request_id': 'models', 'request': {'subtype': 'initialize'}}) + '\n')
        process.stdin.flush()
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            try:
                line = lines.get(timeout=max(0.1, deadline - time.monotonic()))
            except queue.Empty:
                break
            if line is None:
                break
            try:
                value = json.loads(line)
            except ValueError:
                continue
            if value.get('type') != 'control_response':
                continue
            items = value.get('response', {}).get('response', {}).get('models', [])
            return [{'id': m['value'], 'label': m.get('displayName', m['value']),
                     'efforts': m.get('supportedEffortLevels') or [] if m.get('supportsEffort') else [],
                     'effortSupported': m.get('supportsEffort')}
                    for m in items if SAFE.fullmatch(m.get('value', ''))]
    except (OSError, KeyError):
        return []
    finally:
        process.terminate()
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        for stream in (process.stdin, process.stdout):
            try:
                stream.close()
            except OSError:
                pass
    return []

def arguments(provider, binary, config):
    approval = config.get('approvalMode', 'ask')
    if approval not in ('ask', 'allow-all', 'auto-review') or (approval == 'auto-review' and provider != 'claude'):
        raise ValueError('이 공급자가 지원하지 않는 승인 방식입니다.')
    for key in ('model', 'effort', 'sessionId'):
        if config.get(key) and not SAFE.fullmatch(config[key]):
            raise ValueError('잘못된 CLI 옵션입니다: ' + key)
    if provider == 'claude':
        args = [binary, '-p', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose',
                '--include-partial-messages', '--tools', '', '--strict-mcp-config', '--mcp-config', '/workspace/mcp.json',
                '--setting-sources', '', '--settings', '{"disableAllHooks":true}', '--permission-mode',
                'auto' if approval == 'auto-review' else 'dontAsk']
        # Pre-allowing the MCP wildcard skips Claude's classifier. Only manual/all
        # modes delegate approval entirely to Android; auto must pass the native gate.
        if config.get('mode') != 'chat' and approval != 'auto-review':
            args += ['--allowedTools', 'mcp__mobile__*']
        if config.get('sessionId'):
            args += ['--resume', config['sessionId']]
    else:
        args = [binary, '--input-format', 'stream-json', '--output-format', 'stream-json',
                '--agent', 'mobile-room', '--disable-slash-commands']
        if config.get('sessionId'):
            args += ['--conversation', config['sessionId']]
    if config.get('model'):
        args += ['--model', config['model']]
    if config.get('effort'):
        args += ['--effort', config['effort']]
    return args

def configure(provider, config, env):
    server = {'command': '/usr/bin/python3', 'args': ['/workspace/mobile-mcp.py', '/workspace/run.json']}
    mcp = {'mcpServers': {} if config.get('mode') == 'chat' else {'mobile': server}}
    write_json(Path('/workspace/mcp.json'), mcp)
    if provider == 'agy':
        # This HOME belongs only to this integration; never rewrite a user's
        # pre-existing Termux/desktop settings or credentials.
        home = Path(env['HOME'])
        settings_path = home / '.gemini/antigravity-cli/settings.json'
        settings = json.loads(settings_path.read_text(encoding='utf-8')) if settings_path.exists() else {}
        settings.update({
            'toolPermission': 'request-review', 'artifactReviewPolicy': 'asks-for-review',
            'permissions': {'deny': ['command(*)', 'read_file(*)', 'write_file(*)', 'read_url(*)'],
                            'allow': [] if config.get('mode') == 'chat' else ['mcp(mobile/*)']}})
        write_json(settings_path, settings)
        write_json(Path('/workspace/.agents/mcp_config.json'), mcp)
        agent = Path('/workspace/.agents/agents/mobile-room.md')
        agent.parent.mkdir(parents=True, exist_ok=True)
        agent.write_text('---\nname: mobile-room\ndescription: Mobile Codex room participant\nmainAgent: true\nsubagent: false\n'
                         'commandExecutionPolicy: off\ntools: []\n---\n'
                         'Use only the provided mobile MCP tools for project and phone operations.\n', encoding='utf-8')

CLAUDE_USAGE_LINE = re.compile(r'^\s*(\S.*?):\s*(\d{1,3}(?:\.\d+)?)%\s*used(?:\s*[·•|-]\s*resets\s+(.+?))?\s*$', re.I)
CLAUDE_RESET = re.compile(r'^([A-Za-z]{3})[a-z]*\s+(\d{1,2}),?\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)\s*\(UTC\)$', re.I)
MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec']

def claude_reset_time(text, now=None):
    """'Oct 17, 7pm (UTC)' as an ISO time. /usage omits the year: use the next such date."""
    from datetime import datetime, timedelta, timezone
    match = CLAUDE_RESET.match(text.strip())
    if not match or match[1].lower()[:3] not in MONTHS:
        return ''
    now = now or datetime.now(timezone.utc)
    hour = int(match[3]) % 12 + (12 if match[5].lower() == 'pm' else 0)
    try:
        moment = datetime(now.year, MONTHS.index(match[1].lower()[:3]) + 1, int(match[2]), hour, int(match[4] or 0), tzinfo=timezone.utc)
        if moment < now - timedelta(days=1):
            moment = moment.replace(year=now.year + 1)
    except ValueError:
        return ''
    return moment.strftime('%Y-%m-%dT%H:%M:%SZ')

def parse_claude_usage(text, now=None):
    """Rows of `claude -p /usage`, e.g. 'Current week (all models): 40% used · resets Oct 17, 7pm (UTC)'."""
    rows = []
    for line in text.splitlines():
        match = CLAUDE_USAGE_LINE.match(line)
        if not match or len(rows) >= 8:
            continue
        label, reset = match[1].strip()[:80], (match[3] or '').strip()[:60]
        limit = 'week' if 'week' in label.lower() else 'session' if 'session' in label.lower() else ''
        rows.append({'label': label, 'limit': limit, 'remainingPercent': max(0.0, 100.0 - min(100.0, float(match[2]))),
                     'resetsAt': claude_reset_time(reset, now), 'resetText': reset})
    return rows

USAGE_LINE = re.compile(r'^\s*(\S.*?)(?:\t+|\s{2,})(\S.*?)(?:\t+|\s{2,})(\d{1,3}(?:\.\d+)?)%(?:(?:\t+|\s{2,})(\S+))?\s*$')

def parse_agy_usage(text):
    """Rows of `agy -p /usage`: model group, limit name, percent remaining, reset time."""
    rows = []
    for line in text.splitlines():
        match = USAGE_LINE.match(line)
        if match and len(rows) < 8:
            rows.append({'label': match[1].strip()[:80], 'limit': match[2].strip()[:80],
                         'remainingPercent': min(100.0, float(match[3])), 'resetsAt': (match[4] or '')[:40]})
    return rows

def agy_usage(binary, env):
    """Whether agy is signed in, and its usage limits from the same /usage call."""
    code, usage, _ = capture([binary, '-p', '/usage'], env)
    signed_in = code == 0 and bool(usage.strip()) and not re.search(r'authentication required|not logged|sign in|login required', usage, re.I)
    return signed_in, parse_agy_usage(usage) if signed_in else []

def terminal(argv, env):
    from terminal_io import run_terminal
    # Sign-in URLs run to several hundred characters: a wide terminal keeps them on one
    # line, so the app can open or copy them whole.
    return run_terminal(argv, env, lambda text: emit(kind='output', text=text), columns=500,
                        input_result=lambda result: emit(kind='inputResult', **result))

def download_installer(url, installer, env):
    # Match the official curl-based installer. Python urllib is rejected with
    # HTTP 403 by claude.ai even when curl follows the same HTTPS URL successfully.
    result = subprocess.run(['/usr/bin/curl', '--fail', '--show-error', '--silent', '--location',
        '--proto', '=https', '--proto-redir', '=https', '--max-time', '60', '--max-filesize', str(LIMIT),
        '--output', str(installer), url], env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=65)
    if result.returncode:
        installer.unlink(missing_ok=True)
        raise RuntimeError('공식 CLI 설치 파일 다운로드 실패: ' + result.stderr.strip()[:600])
    script = installer.read_bytes()
    if not script or len(script) > LIMIT or not script.startswith(b'#!/'):
        installer.unlink(missing_ok=True)
        raise ValueError('설치 파일 내용 또는 크기가 올바르지 않습니다.')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('provider', choices=['claude', 'agy'])
    parser.add_argument('action', choices=['probe', 'install', 'login', 'run'])
    args = parser.parse_args()
    env = environment(args.provider)
    binary = str(Path(env['HOME']) / '.local/bin' / ('claude' if args.provider == 'claude' else 'agy'))
    if args.action == 'probe':
        emit(kind='probe', **probe(args.provider, binary, env))
    elif args.action == 'install':
        url = 'https://claude.ai/install.sh' if args.provider == 'claude' else 'https://antigravity.google/cli/install.sh'
        installer = Path('/workspace/official-install.sh')
        download_installer(url, installer, env)
        # The agy shell bootstrapper accepts --dir/--help only; its native
        # installer handles interactive shell setup through the same terminal.
        options = ['stable'] if args.provider == 'claude' else []
        sys.exit(terminal(['/bin/bash', str(installer), *options], env))
    elif args.action == 'login':
        # agy has no login command: after signing in it stays open as an agent, so the
        # user ends it with the app's finish button. Never run a second agy alongside it.
        sys.exit(terminal([binary, 'auth', 'login'] if args.provider == 'claude' else [binary], env))
    else:
        config = json.loads(Path('/workspace/run.json').read_text(encoding='utf-8'))
        configure(args.provider, config, env)
        os.execve(binary, arguments(args.provider, binary, config), env)

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        emit(kind='runtime-error', message=str(error)[:1000])
        sys.exit(1)
