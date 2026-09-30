#!/usr/bin/env python3
"""Dependency-free stdio MCP adapter for the app-owned Pro consultation broker.

Only the app's loopback socket is contacted. Secrets, prompts and results are
never logged. Each consultation requires a separately validated app grant.
"""
import argparse
import json
import os
import socket
import sys
import threading
import time
import uuid

MAX_FRAME_BYTES = 1536 * 1024
MAX_PROMPT_CHARS = 50_000
MAX_RESULT_CHARS = 200_000
MAX_PENDING = 2
PROTOCOLS = ('2024-11-05', '2025-03-26', '2025-06-18', '2025-11-25')


def encode(value):
    data = json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')
    if len(data) > MAX_FRAME_BYTES:
        raise ValueError('Message exceeds the local bridge limit.')
    return data + b'\n'


def read_frame(stream):
    data = stream.readline(MAX_FRAME_BYTES + 2)
    if not data:
        return None
    if len(data) > MAX_FRAME_BYTES + 1 or not data.endswith(b'\n'):
        raise ValueError('Invalid or oversized message framing.')
    value = json.loads(data.decode('utf-8'), parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Invalid JSON number.')))
    if not isinstance(value, dict):
        raise ValueError('Expected a JSON object.')
    return value


class Call:
    def __init__(self, request_id, token, secret):
        self.request_id, self.token, self.secret = request_id, token, secret
        self.call_id = uuid.uuid4().hex
        self.cancelled = threading.Event()
        self.expired = threading.Event()
        self.lock = threading.Lock()
        self.socket = None

    def stop(self):
        with self.lock:
            if self.socket is not None:
                try:
                    self.socket.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                self.socket.close()

    def cancel(self):
        self.cancelled.set()
        self.stop()

    def expire(self):
        self.expired.set()
        self.stop()


class Server:
    def __init__(self, port, secret, timeout=650, output=None):
        self.port, self.secret, self.timeout = port, secret, timeout
        self.output = output if output is not None else sys.stdout.buffer
        self.output_lock, self.pending_lock = threading.Lock(), threading.Lock()
        self.pending = {}
        self.initialized = False

    def send(self, value):
        data = encode(value)
        with self.output_lock:
            self.output.write(data)
            self.output.flush()

    def result(self, request_id, result):
        self.send(dict(jsonrpc='2.0', id=request_id, result=result))

    def error(self, request_id, code, message):
        self.send(dict(jsonrpc='2.0', id=request_id, error=dict(code=code, message=message)))

    @staticmethod
    def tool_result(text, error=False):
        return dict(content=[dict(type='text', text=text)], isError=error)

    def consult(self, call, prompt):
        result = self.tool_result('The local Pro consultation bridge is unavailable.', True)
        timeout = threading.Timer(self.timeout, call.expire)
        timeout.daemon = True
        timeout.start()
        try:
            deadline = time.monotonic() + self.timeout
            connection = socket.create_connection(('127.0.0.1', self.port), timeout=min(5, self.timeout))
            with call.lock:
                if call.cancelled.is_set() or call.expired.is_set():
                    connection.close()
                    return
                call.socket = connection
                connection.settimeout(max(0.001, min(5, deadline - time.monotonic())))
                connection.sendall(encode(dict(method='consult', secret=self.secret, callId=call.call_id,
                                               requestToken=call.token, prompt=prompt)))
                connection.settimeout(max(0.001, deadline - time.monotonic()))
            with connection.makefile('rb') as stream:
                response = read_frame(stream)
            if (not isinstance(response, dict) or response.get('callId') != call.call_id
                    or type(response.get('success')) is not bool or not isinstance(response.get('text'), str)
                    or len(response['text']) > MAX_RESULT_CHARS):
                raise ValueError('Invalid local consultation response.')
            result = self.tool_result(response['text'], not response['success'])
        except (socket.timeout, TimeoutError):
            result = self.tool_result('Pro consultation timed out. It was not resent.', True)
        except (OSError, ValueError, UnicodeError):
            # Never expose raw socket/JSON exceptions: they may contain payloads.
            pass
        finally:
            timeout.cancel()
            if call.expired.is_set():
                result = self.tool_result('Pro consultation timed out. It was not resent.', True)
            with call.lock:
                if call.socket is not None:
                    call.socket.close()
                    call.socket = None
            with self.pending_lock:
                # Claim completion under the lock, then release it before stdout
                # writes. A slow MCP reader must not prevent cancelling other calls.
                emit = not call.cancelled.is_set()
                if self.pending.get(call.request_id) is call:
                    del self.pending[call.request_id]
            if emit:
                self.result(call.request_id, result)

    def handle(self, message):
        request_id = message.get('id')
        has_id = 'id' in message
        if (message.get('jsonrpc') != '2.0' or not isinstance(message.get('method'), str)
                or (has_id and (type(request_id) not in (str, int)))):
            self.error(None, -32600, 'Invalid JSON-RPC request.')
            return
        method = message['method']
        params = message.get('params', {})
        if not isinstance(params, dict):
            if has_id:
                self.error(request_id, -32602, 'Invalid request parameters.')
            return
        if method == 'notifications/cancelled' and not has_id:
            with self.pending_lock:
                target = params.get('requestId')
                if type(target) in (str, int):
                    call = self.pending.get(target)
                    if call is not None:
                        call.cancel()
            return
        if not has_id:
            return
        with self.pending_lock:
            if request_id in self.pending:
                self.error(request_id, -32600, 'A request with this ID is already pending.')
                return
        if method == 'initialize':
            requested = params.get('protocolVersion')
            self.initialized = True
            self.result(request_id, dict(protocolVersion=requested if requested in PROTOCOLS else PROTOCOLS[-1],
                                        capabilities=dict(tools=dict(listChanged=False)),
                                        serverInfo=dict(name='mobile-codex-pro', version='1.0.0')))
        elif method == 'ping':
            self.result(request_id, {})
        elif not self.initialized:
            self.error(request_id, -32000, 'Initialize the MCP session first.')
        elif method == 'tools/list':
            self.result(request_id, dict(tools=[dict(
                name='consult_pro',
                description='Consult Pro through the mobile app. Requires the requestToken from an explicit consultation grant. No project files are automatically included.',
                inputSchema=dict(type='object', properties=dict(
                    requestToken=dict(type='string', minLength=1, maxLength=512),
                    prompt=dict(type='string', minLength=1, maxLength=MAX_PROMPT_CHARS)),
                    required=['requestToken', 'prompt'], additionalProperties=False),
                annotations=dict(readOnlyHint=True, destructiveHint=False, idempotentHint=False, openWorldHint=True))]))
        elif method == 'tools/call':
            args = params.get('arguments')
            if (params.get('name') != 'consult_pro' or not isinstance(args, dict)
                    or set(args) != {'requestToken', 'prompt'}
                    or not isinstance(args['requestToken'], str) or not 1 <= len(args['requestToken']) <= 512
                    or not isinstance(args['prompt'], str) or not 1 <= len(args['prompt']) <= MAX_PROMPT_CHARS):
                self.error(request_id, -32602, 'Expected consult_pro with a requestToken and a prompt of at most 50000 characters.')
                return
            with self.pending_lock:
                if len(self.pending) >= MAX_PENDING:
                    self.result(request_id, self.tool_result('The local consultation bridge is busy.', True))
                    return
                call = Call(request_id, args['requestToken'], self.secret)
                self.pending[request_id] = call
            threading.Thread(target=self.consult, args=(call, args['prompt']), daemon=True).start()
        else:
            self.error(request_id, -32601, 'Method not found.')

    def run(self, input_stream=None):
        stream = input_stream if input_stream is not None else sys.stdin.buffer
        try:
            while True:
                try:
                    message = read_frame(stream)
                except (ValueError, UnicodeError):
                    self.error(None, -32700, 'Invalid or oversized JSON message.')
                    # Framing is uncertain: do not interpret the remaining bytes as another request.
                    break
                if message is None:
                    break
                self.handle(message)
        finally:
            with self.pending_lock:
                for call in list(self.pending.values()):
                    call.cancel()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--timeout', type=float, default=650)
    args = parser.parse_args()
    secret = os.environ.get('MC_PRO_CONSULT_SECRET', '')
    if (not 1 <= args.port <= 65535 or not 0 < args.timeout <= 650
            or len(secret) != 64 or any(char not in '0123456789abcdef' for char in secret)):
        parser.error('Invalid local consultation bridge configuration.')
    Server(args.port, secret, args.timeout).run()


if __name__ == '__main__':
    main()
