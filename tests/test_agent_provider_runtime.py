import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('room_runtime', Path(__file__).parents[1] / 'app/src/main/assets/rooms/provider-runtime.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)
sys.path.insert(0, str(Path(runtime.__file__).parent))

class RoomProviderRuntimeTests(unittest.TestCase):
    def test_native_auto_review_is_detected_from_this_clis_permission_choices(self):
        help_text = '--permission-mode <mode> choices: default, acceptEdits, auto, dontAsk\n  --model <model>'
        self.assertTrue(runtime.supports_auto_review('claude', help_text))
        self.assertFalse(runtime.supports_auto_review('agy', help_text))
        self.assertFalse(runtime.supports_auto_review('claude', help_text.replace(', auto', '')))
        self.assertFalse(runtime.supports_auto_review('claude', '--model auto'))

    def test_claude_auto_review_does_not_bypass_the_native_mcp_classifier(self):
        args = runtime.arguments('claude', '/bin/claude', {'mode': 'write', 'approvalMode': 'auto-review'})
        self.assertEqual(args[args.index('--permission-mode') + 1], 'auto')
        self.assertNotIn('--allowedTools', args)
        self.assertEqual(args[args.index('--tools') + 1], '')
        self.assertIn('--strict-mcp-config', args)
        self.assertNotIn('--dangerously-skip-permissions', args)

    def test_all_allowed_still_uses_host_gated_tools_and_agy_has_no_fake_auto_review(self):
        for provider in ['claude', 'agy']:
            args = runtime.arguments(provider, '/bin/cli', {'mode': 'write', 'approvalMode': 'allow-all'})
            self.assertNotIn('--dangerously-skip-permissions', args)
        with self.assertRaises(ValueError):
            runtime.arguments('agy', '/bin/agy', {'approvalMode': 'auto-review'})
        with self.assertRaises(ValueError):
            runtime.arguments('claude', '/bin/claude', {'approvalMode': 'invalid'})

    @unittest.skipUnless(os.name == 'posix', 'PTY and /dev/tty require a POSIX host')
    def test_setup_terminal_delivers_batched_answers_to_controlling_tty_without_echo(self):
        child = '''
import os
tty = os.open('/dev/tty', os.O_RDWR)
assert os.isatty(0)
for expected in [b'y', b'n', b'', b'private-code']:
    os.write(tty, b'Continue? [y/n] ')
    answer = b''
    while not answer.endswith(b'\\n'):
        answer += os.read(tty, 1)
    assert answer.rstrip(b'\\r\\n') == expected, repr(answer)
os.write(tty, b'\\nINSTALL_FINISHED\\n')
'''
        wrapper = '''
import os, runpy, sys
# Match Python's normal script invocation: adjacent helper modules are importable.
sys.path.insert(0, os.path.dirname(os.path.abspath(sys.argv[1])))
runtime = runpy.run_path(sys.argv[1])
sys.exit(runtime['terminal']([sys.executable, '-c', sys.argv[2]], dict(os.environ)))
'''
        process = subprocess.Popen([sys.executable, '-u', '-c', wrapper, str(Path(runtime.__file__).resolve()), child],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            process.stdin.write(''.join(json.dumps({'text': text}) + '\n' for text in ['y\n', 'n\n', '\n', 'private-code\n']))
            process.stdin.flush()
            self.assertEqual(process.wait(timeout=8), 0, process.stderr.read())
            output = ''.join(json.loads(line).get('text', '') for line in process.stdout)
            self.assertIn('INSTALL_FINISHED', output)
            self.assertNotIn('private-code', output)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            for pipe in [process.stdin, process.stdout, process.stderr]:
                pipe.close()

    def test_agy_installer_does_not_receive_unsupported_shell_bootstrap_flags(self):
        with tempfile.TemporaryDirectory() as directory:
            installer = Path(directory) / 'official-install.sh'
            with patch.object(sys, 'argv', ['provider-runtime.py', 'agy', 'install']), \
                 patch.object(runtime, 'environment', return_value={'HOME': directory}), \
                 patch.object(runtime, 'download_installer') as download, \
                 patch.object(runtime, 'Path', return_value=installer), \
                 patch.object(runtime, 'terminal', return_value=0) as terminal:
                with self.assertRaises(SystemExit) as result:
                    runtime.main()
                self.assertEqual(result.exception.code, 0)
                download.assert_called_once_with('https://antigravity.google/cli/install.sh', installer, {'HOME': directory})
                terminal.assert_called_once_with(['/bin/bash', str(installer)], {'HOME': directory})

    def test_download_uses_official_curl_path_with_https_and_limits(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'install.sh'
            def run(argv, **options):
                self.assertEqual(argv[0], '/usr/bin/curl')
                self.assertIn('--fail', argv)
                self.assertIn('--location', argv)
                self.assertIn('--max-filesize', argv)
                self.assertEqual(argv[argv.index('--proto-redir') + 1], '=https')
                self.assertNotIn('--insecure', argv)
                target.write_bytes(b'#!/bin/bash\nexit 0\n')
                return subprocess.CompletedProcess(argv, 0, '', '')
            with patch.object(runtime.subprocess, 'run', side_effect=run):
                runtime.download_installer('https://claude.ai/install.sh', target, {})
            self.assertTrue(target.is_file())

    def test_failed_or_html_download_never_reuses_previous_installer(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'install.sh'
            for code, content in [(22, b'#!/bin/bash\nold'), (0, b'<html>Forbidden</html>')]:
                target.write_bytes(content)
                with patch.object(runtime.subprocess, 'run', return_value=subprocess.CompletedProcess([], code, '', 'HTTP 403')):
                    with self.assertRaises((RuntimeError, ValueError)):
                        runtime.download_installer('https://claude.ai/install.sh', target, {})
                self.assertFalse(target.exists())

    def test_claude_persists_and_resumes_specific_session_with_only_gated_mcp(self):
        args = runtime.arguments('claude', '/bin/claude', {'mode': 'write', 'sessionId': 'session-1', 'model': 'actual', 'effort': 'high'})
        self.assertEqual(args[args.index('--resume') + 1], 'session-1')
        self.assertEqual(args[args.index('--tools') + 1], '')
        self.assertEqual(args[args.index('--permission-mode') + 1], 'dontAsk')
        self.assertNotIn('--no-session-persistence', args)
        self.assertNotIn('--dangerously-skip-permissions', args)
        self.assertIn('mcp__mobile__*', args)

    def test_antigravity_stream_input_has_no_print_prompt_and_uses_conversation(self):
        args = runtime.arguments('agy', '/bin/agy', {'sessionId': 'existing', 'mode': 'chat'})
        self.assertNotIn('-p', args)
        self.assertEqual(args[args.index('--input-format') + 1], 'stream-json')
        self.assertEqual(args[args.index('--conversation') + 1], 'existing')
        self.assertEqual(args[args.index('--agent') + 1], 'mobile-room')
        self.assertFalse(any('dangerously' in arg for arg in args))

    def test_options_cannot_inject_flags_or_shell_syntax(self):
        for key in ['model', 'effort', 'sessionId']:
            with self.assertRaises(ValueError):
                runtime.arguments('claude', '/bin/claude', {key: '--dangerously-skip-permissions'})
            with self.assertRaises(ValueError):
                runtime.arguments('agy', '/bin/agy', {key: 'abc; touch stolen'})

    def test_agy_probe_reads_usage_printed_on_stderr(self):
        # agy 1.3 prints its flag list to stderr; the probe must not call it outdated.
        help_text = 'Usage of agy:\n  --conversation  Resume\n  --input-format  x\n  --output-format  y\n'
        answers = {'--version': (0, '1.3.5\n', ''), '--help': (0, '', help_text),
                   'models': (0, 'gemini-3.8-flash-high\tGemini 3.8 Flash (High)\n', ''),
                   # The real /usage output: a spinner line, then tab-separated rows.
                   '-p': (0, '⠋ Fetching usage...\nGemini Models\tWeekly Limit Remaining\t92%\t2026-10-21T13:00:21Z\n'
                             'Claude and GPT models\tWeekly Limit Remaining\t100%\t2026-10-22T02:52:21Z\n', '')}
        with tempfile.NamedTemporaryFile() as binary, \
             patch.object(runtime, 'capture', side_effect=lambda argv, env, timeout=20: answers[argv[1]]):
            result = runtime.probe('agy', binary.name, {})
        self.assertEqual(result['state'], 'ready')
        self.assertEqual(result['models'][0]['id'], 'gemini-3.8-flash-high')
        self.assertEqual(result['usage'], [
            {'label': 'Gemini Models', 'limit': 'Weekly Limit Remaining', 'remainingPercent': 92.0, 'resetsAt': '2026-10-21T13:00:21Z'},
            {'label': 'Claude and GPT models', 'limit': 'Weekly Limit Remaining', 'remainingPercent': 100.0, 'resetsAt': '2026-10-22T02:52:21Z'}])

    def test_agy_usage_is_empty_when_signed_out_and_ignores_other_lines(self):
        self.assertEqual(runtime.agy_status(1, 'Authentication required. Please sign in.\n', ''), (False, []))
        self.assertEqual(runtime.parse_agy_usage('Remaining credits: lots\nnot a usage row\n'), [])

    def test_agy_failures_that_say_nothing_about_the_account_are_not_a_sign_out(self):
        for code, out, err in [(124, '', '시간 초과'), (-9, '', ''), (1, '', 'boom'), (0, '  \n', ''),
                               (1, '', 'Could not sign in: network unreachable'), (1, '', 'connect ETIMEDOUT'),
                               (1, '', 'Could not sign in: service unavailable')]:
            self.assertEqual(runtime.agy_status(code, out, err), (None, []), (code, out, err))
        self.assertEqual(runtime.agy_status(1, '', 'Please log in first'), (False, []))
        self.assertEqual(runtime.agy_status(1, '', 'catalog in use'), (None, []))

    def probe_with(self, provider, answers):
        table = {'--version': (0, '1.0\n', ''), 'auth': (0, json.dumps({'loggedIn': True}), ''), 'models': (0, '', ''), '-p': (0, 'Gemini\tWeekly\t50%\tsoon\n', ''),
                 '--help': (0, '--input-format --output-format --resume --conversation', ''), **answers}
        with tempfile.NamedTemporaryFile() as binary, patch.object(runtime, 'claude_models', return_value=[]), \
             patch.object(runtime, 'capture', side_effect=lambda argv, env, timeout=20: table[argv[1]]):
            return runtime.probe(provider, binary.name, {})

    def test_a_slow_cli_is_unverified_rather_than_signed_out_or_outdated(self):
        slow = (124, '', '시간 초과')
        cases = [('claude', {'auth': slow}), ('agy', {'-p': slow}), ('claude', {'--version': slow}), ('agy', {'--help': slow})]
        for provider, answers in cases:
            result = self.probe_with(provider, answers)
            self.assertEqual((result['state'], result['auth']), ('unverified', 'unknown'), (provider, answers))
            self.assertIn('시간 초과', ''.join(step['error'] for step in result['steps']))

    def test_only_the_cli_itself_can_report_a_claude_sign_out(self):
        for answer, state in [((1, json.dumps({'loggedIn': False}), ''), 'login_required'), ((0, json.dumps({'loggedIn': True}), ''), 'ready'),
                              ((1, 'not json', 'crash'), 'unverified'), ((0, '[]', ''), 'unverified')]:
            self.assertEqual(self.probe_with('claude', {'auth': answer})['state'], state, answer)

    def test_interrupted_auth_and_network_failures_never_report_signed_out(self):
        self.assertIsNone(runtime.claude_authenticated(124, '{"loggedIn":false}'))
        self.assertIsNone(runtime.claude_authenticated(-9, '{"loggedIn":false}'))
        for step in ('--version', '--help'):
            result = self.probe_with('claude', {step: (1, '', 'network unreachable')})
            self.assertEqual((result['state'], result['auth']), ('unverified', 'unknown'))

    def test_probe_steps_share_one_budget_below_the_node_limit(self):
        limit = int(runtime.re.search(r'const probeLimit=(\d+)', Path(runtime.__file__).with_name('providers.cjs').read_text(encoding='utf-8'))[1])
        self.assertLess(runtime.PROBE_SECONDS * 1000, limit)
        seen = []
        def capture(argv, env, timeout=20):
            seen.append(timeout)
            return {'--version': (0, '1', ''), '--help': (0, '--input-format --output-format --resume', ''), 'auth': (0, '{"loggedIn":true}', ''), '-p': (0, '', '')}[argv[1]]
        clock = iter(range(0, 1000, 40))
        with tempfile.NamedTemporaryFile() as binary, patch.object(runtime, 'capture', side_effect=capture), \
             patch.object(runtime, 'claude_models', return_value=[]) as models, patch.object(runtime.time, 'monotonic', side_effect=lambda: next(clock)):
            runtime.probe('claude', binary.name, {})
        self.assertLessEqual(max(seen), runtime.STEP_SECONDS)
        self.assertEqual(seen[-1], 1)  # Out of budget: the last step gets only a token second.
        self.assertLessEqual(models.call_args[0][2], runtime.STEP_SECONDS)

    def test_claude_models_waits_for_a_slow_cli(self):
        # Under PRoot the CLI can take seconds before its first line.
        fake = ('import json,sys,time\n'
                'sys.stdin.readline(); time.sleep(2.5)\n'
                'print(json.dumps({"type":"system"})); print(json.dumps({"type":"control_response","response":{"response":{"models":['
                '{"value":"opus","displayName":"Opus 5.5","supportsEffort":True,"supportedEffortLevels":["low","max"]},'
                '{"value":"haiku","displayName":"Haiku 4.5","supportsEffort":None,"supportedEffortLevels":None}]}}}), flush=True)\n'
                'time.sleep(30)\n')
        real = subprocess.Popen
        with tempfile.TemporaryDirectory() as folder:
            script = Path(folder, 'fake_claude.py'); script.write_text(fake, encoding='utf-8')
            with patch.object(runtime.subprocess, 'Popen', side_effect=lambda argv, **kw: real([sys.executable, str(script), *argv[1:]], **kw)):
                models = runtime.claude_models('claude', dict(os.environ))
        self.assertEqual([m['id'] for m in models], ['opus', 'haiku'])
        self.assertEqual(models[0]['efforts'], ['low', 'max'])
        self.assertEqual(models[1]['efforts'], [])

    def test_claude_usage_rows_from_the_real_usage_output(self):
        from datetime import datetime, timezone
        report = ('You are currently using your subscription to power your Claude Code usage\n\n'
                  'Current session: 86% used · resets Oct 15, 5:50am (UTC)\n'
                  'Current week (all models): 40% used · resets Oct 17, 7pm (UTC)\n')
        rows = runtime.parse_claude_usage(report, now=datetime(2026, 10, 15, 2, 0, tzinfo=timezone.utc))
        self.assertEqual(rows, [
            {'label': 'Current session', 'limit': 'session', 'remainingPercent': 14.0,
             'resetsAt': '2026-10-15T05:50:00Z', 'resetText': 'Oct 15, 5:50am (UTC)'},
            {'label': 'Current week (all models)', 'limit': 'week', 'remainingPercent': 60.0,
             'resetsAt': '2026-10-17T19:00:00Z', 'resetText': 'Oct 17, 7pm (UTC)'}])

    def test_claude_reset_time_without_a_year_rolls_into_the_next_year(self):
        from datetime import datetime, timezone
        december = datetime(2026, 12, 30, 12, 0, tzinfo=timezone.utc)
        self.assertEqual(runtime.claude_reset_time('Jan 2, 12am (UTC)', december), '2027-01-02T00:00:00Z')
        self.assertEqual(runtime.claude_reset_time('Dec 30, 12pm (UTC)', december), '2026-12-30T12:00:00Z')
        # Unknown formats keep only the CLI's own text for display.
        self.assertEqual(runtime.claude_reset_time('in 3 hours', december), '')
        rows = runtime.parse_claude_usage('Current session: 100% used · resets in 3 hours\nnot a usage line\n')
        self.assertEqual(rows, [{'label': 'Current session', 'limit': 'session', 'remainingPercent': 0.0, 'resetsAt': '', 'resetText': 'in 3 hours'}])

    def test_claude_probe_reports_usage_only_when_signed_in(self):
        help_text = 'Usage: claude\n  --input-format\n  --output-format\n  --resume\n  --model <model>  e.g. "opus"\n'
        report = 'Current week (all models): 40% used · resets Oct 17, 7pm (UTC)\n'
        def answers(signed_in):
            table = {'--version': (0, '2.1.292 (Claude Code)\n', ''), '--help': (0, help_text, ''),
                     'auth': (0, json.dumps({'loggedIn': signed_in}), ''), '-p': (0, report, '')}
            return lambda argv, env, timeout=20: table[argv[1]]
        with tempfile.NamedTemporaryFile() as binary, patch.object(runtime, 'claude_models', return_value=[]):
            with patch.object(runtime, 'capture', side_effect=answers(True)):
                signed_in = runtime.probe('claude', binary.name, {})
            with patch.object(runtime, 'capture', side_effect=answers(False)):
                signed_out = runtime.probe('claude', binary.name, {})
        self.assertEqual([row['remainingPercent'] for row in signed_in['usage']], [60.0])
        self.assertEqual(signed_out['usage'], [])

if __name__ == '__main__':
    unittest.main()
