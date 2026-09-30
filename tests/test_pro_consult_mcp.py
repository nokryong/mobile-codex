import json
import os
from pathlib import Path
import queue
import re
import socket
import subprocess
import sys
import threading
import time
import unittest


SCRIPT = Path(__file__).parents[1] / 'app/src/main/assets/pro-consult-mcp.py'
SECRET = 'a' * 64


class Broker:
    """Real loopback transport fixture; no external service or web request."""
    def __init__(self, callback=None, secret=SECRET):
        self.callback, self.secret = callback, secret
        self.calls = queue.Queue()
        self.disconnected = threading.Event()
        self.closed = threading.Event()
        self.clients = []
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0))
        self.listener.listen(4)
        self.listener.settimeout(0.1)
        self.port = self.listener.getsockname()[1]
        self.thread = threading.Thread(target=self.accept, daemon=True)
        self.thread.start()

    def accept(self):
        while not self.closed.is_set():
            try:
                connection, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            self.clients.append(connection)
            threading.Thread(target=self.serve, args=(connection,), daemon=True).start()

    def serve(self, connection):
        try:
            connection.settimeout(2)
            with connection, connection.makefile('rb') as stream:
                request = json.loads(stream.readline())
                self.calls.put(request)
                if request.get('secret') != self.secret:
                    response = dict(success=False, text='Local consultation authentication failed.')
                elif self.callback:
                    response = self.callback(connection, request)
                else:
                    response = dict(success=True, text='상담 결과')
                if response is not None:
                    response.setdefault('callId', request['callId'])
                    connection.sendall(json.dumps(response, ensure_ascii=False).encode('utf-8') + b'\n')
        except (OSError, ValueError):
            pass

    def close(self):
        self.closed.set()
        self.listener.close()
        for connection in self.clients:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        self.thread.join(1)


class Mcp:
    def __init__(self, port, timeout=2, secret=SECRET):
        self.process = subprocess.Popen([sys.executable, '-u', str(SCRIPT), '--port', str(port), '--timeout', str(timeout)],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                        env=os.environ | {'MC_PRO_CONSULT_SECRET': secret})
        self.messages = queue.Queue()
        self.stderr = None
        self.thread = threading.Thread(target=self.read, daemon=True)
        self.thread.start()

    def read(self):
        for line in self.process.stdout:
            try:
                self.messages.put(json.loads(line))
            except ValueError as error:
                self.messages.put(error)

    def send(self, method, params=None, request_id=None):
        value = dict(jsonrpc='2.0', method=method)
        if params is not None:
            value['params'] = params
        if request_id is not None:
            value['id'] = request_id
        self.process.stdin.write(json.dumps(value, ensure_ascii=False).encode('utf-8') + b'\n')
        self.process.stdin.flush()

    def receive(self, timeout=3):
        value = self.messages.get(timeout=timeout)
        if isinstance(value, Exception):
            raise value
        return value

    def initialize(self):
        self.send('initialize', dict(protocolVersion='2025-06-18', capabilities={}, clientInfo=dict(name='test', version='1')), 1)
        value = self.receive()
        self.send('notifications/initialized')
        return value

    def call(self, request_id=2, prompt='검토해 주세요', token='explicit-app-grant'):
        self.send('tools/call', dict(name='consult_pro', arguments=dict(requestToken=token, prompt=prompt)), request_id)

    def close(self):
        if self.stderr is not None:
            return self.stderr
        if not self.process.stdin.closed:
            self.process.stdin.close()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=3)
        self.thread.join(1)
        stderr = self.stderr = self.process.stderr.read()
        self.process.stdout.close()
        self.process.stderr.close()
        return stderr


class ProConsultMcpTests(unittest.TestCase):
    def setup_bridge(self, callback=None, timeout=2, broker_secret=SECRET):
        broker = Broker(callback, broker_secret)
        self.addCleanup(broker.close)
        client = Mcp(broker.port, timeout)
        self.addCleanup(client.close)
        return broker, client

    def test_stdio_initialize_single_tool_schema_ping_and_unknown_method(self):
        broker, client = self.setup_bridge()
        initialized = client.initialize()['result']
        self.assertEqual('2025-06-18', initialized['protocolVersion'])
        self.assertEqual({'tools': {'listChanged': False}}, initialized['capabilities'])
        client.send('tools/list', {}, 2)
        tools = client.receive()['result']['tools']
        self.assertEqual(['consult_pro'], [tool['name'] for tool in tools])
        schema = tools[0]['inputSchema']
        self.assertEqual(['requestToken', 'prompt'], schema['required'])
        self.assertEqual(50_000, schema['properties']['prompt']['maxLength'])
        self.assertFalse(schema['additionalProperties'])
        client.send('ping', {}, 3)
        self.assertEqual({}, client.receive()['result'])
        client.send('resources/list', {}, 4)
        self.assertEqual(-32601, client.receive()['error']['code'])
        self.assertTrue(broker.calls.empty())

    def test_mcp_runtime_allowlist_is_prepared_by_devtools_and_contains_no_credentials(self):
        source_root = SCRIPT.parents[1] / 'java/dev/mobilecodex/app'
        bridge = (source_root / 'ProConsultMcp.java').read_text(encoding='utf-8')
        devtools = (source_root / 'DevTools.java').read_text(encoding='utf-8')
        declaration = re.search(r'RUNTIME_ENV_KEYS = List\.of\((.*?)\);', bridge, re.S).group(1)
        forwarded = set(re.findall(r'"([A-Z0-9_]+)"', declaration))
        prepared = set(re.findall(r'env\.put\("([A-Z0-9_]+)"', devtools))
        self.assertLessEqual(forwarded, prepared, 'Every forwarded value must come from the prepared app runtime')
        self.assertLessEqual({'PYTHONHOME', 'PYTHONUTF8', 'LD_LIBRARY_PATH', 'LD_PRELOAD',
                              'MC_PREFIX', 'MC_NATIVE_DIR', 'MC_PYTHON'}, forwarded)
        self.assertFalse(forwarded & {'CODEX_HOME', 'OPENAI_API_KEY', 'GITHUB_TOKEN', 'GITHUB_OAUTH_CLIENT_ID',
                                     'MC_PRO_CONSULT_SECRET'})
        self.assertIn('env_vars=', bridge)

    def test_real_authenticated_loopback_call_maps_text_and_never_logs_secret(self):
        broker, client = self.setup_bridge()
        client.initialize()
        client.call(prompt='프로젝트 상담용 질문')
        result = client.receive()['result']
        self.assertEqual({'content': [{'type': 'text', 'text': '상담 결과'}], 'isError': False}, result)
        sent = broker.calls.get(timeout=1)
        self.assertEqual(SECRET, sent['secret'])
        self.assertEqual('explicit-app-grant', sent['requestToken'])
        self.assertEqual('프로젝트 상담용 질문', sent['prompt'])
        self.assertEqual('consult', sent['method'])
        self.assertRegex(sent['callId'], r'^[a-f0-9]{32}$')
        self.assertEqual(b'', client.close())

    def test_native_failure_and_auth_failure_are_tool_errors(self):
        for callback, expected_secret, text in [
            (lambda *_: dict(success=False, text='Consultation grant expired.'), SECRET, 'Consultation grant expired.'),
            (None, 'b' * 64, 'Local consultation authentication failed.'),
        ]:
            with self.subTest(text=text):
                _, client = self.setup_bridge(callback, broker_secret=expected_secret)
                client.initialize()
                client.call()
                result = client.receive()['result']
                self.assertTrue(result['isError'])
                self.assertEqual(text, result['content'][0]['text'])

    def test_validates_schema_before_connecting_and_accepts_exact_unicode_limit(self):
        broker, client = self.setup_bridge()
        client.initialize()
        invalid = [dict(requestToken='grant', prompt='x' * 50_001), dict(requestToken='', prompt='question'),
                   dict(requestToken='grant', prompt=123), dict(requestToken='grant', prompt='ok', path='/secret')]
        for index, args in enumerate(invalid, 2):
            client.send('tools/call', dict(name='consult_pro', arguments=args), index)
            self.assertEqual(-32602, client.receive()['error']['code'])
        self.assertTrue(broker.calls.empty())
        client.call(10, '😀' * 50_000)
        self.assertFalse(client.receive()['result']['isError'])
        self.assertEqual(50_000, len(broker.calls.get(timeout=1)['prompt']))

    def test_large_valid_result_is_complete_including_json_escaped_characters(self):
        for text in ['한' * 200_000, '😀' * 200_000, '\x00' * 200_000]:
            with self.subTest(character=repr(text[:1])):
                _, client = self.setup_bridge(lambda *_, text=text: dict(success=True, text=text))
                client.initialize()
                client.call()
                result = client.receive()['result']
                self.assertFalse(result['isError'])
                self.assertEqual(text, result['content'][0]['text'])

    def test_invalid_or_oversized_native_response_becomes_bounded_tool_error(self):
        for response in [dict(success=True, text='x' * 200_001), dict(success='yes', text='bad'),
                         dict(success=True, text='bad', callId='other')]:
            with self.subTest(response_type=type(response['success'])):
                _, client = self.setup_bridge(lambda *_, response=response: response)
                client.initialize()
                client.call()
                result = client.receive()['result']
                self.assertTrue(result['isError'])
                self.assertLess(len(result['content'][0]['text']), 200)

    def test_cancellation_closes_socket_and_does_not_emit_late_tool_response(self):
        disconnected = threading.Event()

        def await_disconnect(connection, _):
            if connection.recv(1) == b'':
                disconnected.set()
            return None

        broker, client = self.setup_bridge(await_disconnect)
        client.initialize()
        client.call()
        broker.calls.get(timeout=1)
        client.send('notifications/cancelled', dict(requestId=2, reason='user stopped'))
        self.assertTrue(disconnected.wait(1), 'Native bridge must observe cancellation promptly')
        client.send('ping', {}, 3)
        self.assertEqual(3, client.receive()['id'])
        with self.assertRaises(queue.Empty):
            client.receive(0.05)

    def test_timeout_is_absolute_even_when_peer_trickles_response(self):
        def trickle(connection, _):
            for _ in range(30):
                connection.sendall(b' ')
                time.sleep(0.025)
            return None

        _, client = self.setup_bridge(trickle, timeout=0.15)
        client.initialize()
        start = time.monotonic()
        client.call()
        result = client.receive(1)['result']
        self.assertTrue(result['isError'])
        self.assertIn('timed out', result['content'][0]['text'])
        self.assertLess(time.monotonic() - start, 0.7)

    def test_pending_quota_and_duplicate_id_do_not_forward_extra_requests(self):
        release = threading.Event()
        broker, client = self.setup_bridge(lambda *_: (release.wait(1), dict(success=True, text='done'))[1])
        self.addCleanup(release.set)
        client.initialize()
        client.call(2)
        client.call(3)
        broker.calls.get(timeout=1)
        broker.calls.get(timeout=1)
        client.call(2)
        self.assertEqual(-32600, client.receive()['error']['code'])
        client.call(4)
        self.assertTrue(client.receive()['result']['isError'])
        self.assertTrue(broker.calls.empty())
        release.set()
        self.assertEqual({2, 3}, {client.receive()['id'], client.receive()['id']})

    def test_uninitialized_and_bad_framing_never_reach_native(self):
        broker, client = self.setup_bridge()
        client.call()
        self.assertEqual(-32000, client.receive()['error']['code'])
        client.process.stdin.write(b'not json\n')
        client.process.stdin.flush()
        self.assertEqual(-32700, client.receive()['error']['code'])
        self.assertTrue(broker.calls.empty())
        self.assertEqual(b'', client.close())


if __name__ == '__main__':
    unittest.main()
