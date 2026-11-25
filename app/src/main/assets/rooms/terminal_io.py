"""Line-oriented PTY transport shared by setup and the app terminal.

The WebView is a transcript, not a terminal emulator. Answer terminal queries
here before removing control sequences from the text shown in the WebView.
"""
import codecs
import errno
import json
import os
import re
import selectors
import signal
import subprocess
import sys
import time
import unicodedata
from urllib.parse import parse_qs, urlsplit


class OnboardingScreen:
    """Bounded text screen for AGY's setup menus; never forward terminal escapes."""
    def __init__(self, columns, rows=24):
        self.columns, self.rows = columns, rows
        self.main = None
        self.cursor_keys = False
        self.reset()

    def reset(self):
        self.cells = [[' '] * self.columns for _ in range(self.rows)]
        self.row = self.col = 0
        self.saved = (0, 0)

    def _down(self):
        if self.row + 1 == self.rows:
            self.cells.pop(0)
            self.cells.append([' '] * self.columns)
        else:
            self.row += 1

    def put(self, text):
        for char in text:
            if char == '\r':
                self.col = 0
            elif char == '\n':
                self._down()
            elif char == '\b':
                self.col = max(0, self.col - 1)
            elif char == '\t':
                self.col = min(self.columns - 1, (self.col // 8 + 1) * 8)
            elif ord(char) >= 32 and not 127 <= ord(char) < 160:
                if unicodedata.combining(char):
                    # Bound the output even if a child floods one combining cell.
                    if self.col and len(self.cells[self.row][self.col - 1]) < 8:
                        self.cells[self.row][self.col - 1] += char
                    continue
                width = 2 if unicodedata.east_asian_width(char) in ('W', 'F') else 1
                if self.col + width > self.columns:
                    self.col = 0
                    self._down()
                self.cells[self.row][self.col] = char
                if width == 2:
                    self.cells[self.row][self.col + 1] = ''
                self.col += width

    def csi(self, command):
        code, params = command[-1], command[:-1]
        if params.startswith('?') and code in 'hl':
            modes = params[1:].split(';')
            if '1' in modes:
                self.cursor_keys = code == 'h'
            if any(mode in modes for mode in ('47', '1047', '1049')):
                if code == 'h' and self.main is None:
                    self.main = (self.cells, self.row, self.col, self.saved)
                    self.reset()
                elif code == 'l' and self.main is not None:
                    self.cells, self.row, self.col, self.saved = self.main
                    self.main = None
            return
        if code not in 'HfABEFCDGdsuJKX' or not re.fullmatch(r'[0-9;]*', params):
            return
        values = [min(int(p or '0'), 10000) for p in params.split(';')]
        n = values[0] or 1
        if code in 'Hf':
            self.row = n - 1
            self.col = (values[1] or 1) - 1 if len(values) > 1 else 0
        elif code in 'ABEF':
            self.row += n if code in 'BE' else -n
            if code in 'EF':
                self.col = 0
        elif code in 'CD':
            self.col += n if code == 'C' else -n
        elif code == 'G':
            self.col = n - 1
        elif code == 'd':
            self.row = n - 1
        elif code == 's':
            self.saved = (self.row, self.col)
        elif code == 'u':
            self.row, self.col = self.saved
        elif code in 'JKX':
            col = min(self.col, self.columns - 1)
            mode = values[0]
            if code == 'X':
                end = min(col + n, self.columns)
                self.cells[self.row][col:end] = [' '] * (end - col)
            elif mode == 0:
                self.cells[self.row][col:] = [' '] * (self.columns - col)
                if code == 'J':
                    for row in range(self.row + 1, self.rows):
                        self.cells[row] = [' '] * self.columns
            elif mode == 1:
                self.cells[self.row][:col + 1] = [' '] * (col + 1)
                if code == 'J':
                    for row in range(self.row):
                        self.cells[row] = [' '] * self.columns
            elif mode == 2:
                for row in range(self.rows) if code == 'J' else (self.row,):
                    self.cells[row] = [' '] * self.columns
        self.row = max(0, min(self.row, self.rows - 1))
        self.col = max(0, min(self.col, self.columns - 1))

    def snapshot(self):
        return '\n'.join(''.join(line).rstrip() for line in self.cells).rstrip()


def agy_oauth_link(url):
    # Only the complete Google OAuth target inside an OSC 8 hyperlink is trusted
    # as a link. Painted text may be split, scrolled out, or only partly redrawn.
    if len(url) > 4096 or not url.isascii() or re.search(r'[\s<>"\x00-\x1f\x7f]', url) or "'" in url:
        return False
    try:
        parts = urlsplit(url)
        if (parts.scheme, parts.netloc, parts.path, parts.fragment) != (
                'https', 'accounts.google.com', '/o/oauth2/auth', ''):
            return False
        query = parse_qs(parts.query, keep_blank_values=True, max_num_fields=40)
        def one(key):
            values = query.get(key, [])
            return values[0] if len(values) == 1 else ''
        return (one('redirect_uri') == 'https://antigravity.google/oauth-callback'
                and one('response_type') == 'code' and one('code_challenge_method') == 'S256'
                and all(one(key) for key in ('client_id', 'state', 'code_challenge', 'scope')))
    except ValueError:
        return False


class TerminalProtocol:
    def __init__(self, reply, screen=None, login_link=None):
        self.reply = reply
        self.screen = screen
        self.login_link = login_link
        self.last_login_link = None
        self.pending = ''

    def input_bytes(self, data):
        # Raw CLI key handlers recognize CR as Enter. Canonical terminals map it back to LF.
        data = data.replace(b'\r\n', b'\n').replace(b'\n', b'\r')
        if self.screen is not None and self.screen.cursor_keys and data in (b'\x1b[A', b'\x1b[B'):
            return b'\x1bO' + data[-1:]
        return data

    def feed(self, text):
        text = self.pending + text
        self.pending = ''
        output = []
        index = 0
        while index < len(text):
            char = text[index]
            if char != '\x1b':
                if self.screen is not None:
                    self.screen.put(char)
                if char in '\n\r\t' or ord(char) >= 32:
                    output.append(char)
                index += 1
                continue
            start = index
            if index + 1 == len(text):
                self.pending = text[start:]
                break
            kind = text[index + 1]
            if kind in ']P_^':
                end = re.search(r'\x07|\x1b\\', text[index + 2:])
                if end is None:
                    # Bound incomplete/untrusted control strings, including OSC 52.
                    self.pending = text[start:] if len(text) - start <= 8192 else ''
                    break
                payload = text[index + 2:index + 2 + end.start()]
                terminator = end.group()
                if kind == ']':
                    fields = payload.split(';')
                    if fields[0] == '8' and len(fields) >= 3 and self.login_link is not None:
                        link = payload.split(';', 2)[-1]
                        if link != self.last_login_link and agy_oauth_link(link):
                            self.last_login_link = link
                            self.login_link(link)
                    if fields[0] in ('10', '11', '12'):
                        for offset, value in enumerate(fields[1:]):
                            code = int(fields[0]) + offset
                            if value == '?' and code in (10, 11, 12):
                                color = '0000/0000/0000' if code == 11 else 'ffff/ffff/ffff'
                                self.reply(('\x1b]' + str(code) + ';rgb:' + color + terminator).encode())
                index += 2 + end.end()
            elif kind == '[':
                end = re.search(r'[@-~]', text[index + 2:])
                if end is None:
                    self.pending = text[start:] if len(text) - start <= 256 else ''
                    break
                command = text[index + 2:index + 3 + end.start()]
                replies = {'5n': b'\x1b[0n', '6n': b'\x1b[1;1R', '?6n': b'\x1b[?1;1R',
                           'c': b'\x1b[?1;2c', '0c': b'\x1b[?1;2c', '>c': b'\x1b[>0;0;0c',
                           '>0c': b'\x1b[>0;0;0c', '18t': b'\x1b[8;24;80t'}
                if self.screen is not None:
                    screen = self.screen
                    if command in ('6n', '?6n'):
                        replies[command] = f'\x1b[{command[:-2]}{screen.row + 1};{min(screen.col + 1, screen.columns)}R'.encode()
                    elif command == '18t':
                        replies[command] = f'\x1b[8;{screen.rows};{screen.columns}t'.encode()
                    else:
                        screen.csi(command)
                if command in replies:
                    self.reply(replies[command])
                index += 3 + end.start()
            elif kind in '()*+':
                if index + 2 == len(text):
                    self.pending = text[start:]
                    break
                index += 3
            else:
                if self.screen is not None and kind in '78':
                    self.screen.csi('s' if kind == '7' else 'u')
                index += 2
        return ''.join(output)


MAX_PENDING_INPUT = 65536


def flush_pending(fd, pending):
    try:
        count = os.write(fd, pending)
        del pending[:count]
    except BlockingIOError:
        pass
    except OSError as error:
        if error.errno not in (errno.EIO, errno.EBADF):
            raise
        pending.clear()


def run_terminal(argv, env, output, json_input=True, columns=80, input_result=None, screen_output=None, login_link=None):
    import fcntl
    import pty
    import struct
    import termios
    stopping_at = None

    def stop(*_):
        nonlocal stopping_at
        if stopping_at is None:
            stopping_at = time.monotonic()

    previous = signal.signal(signal.SIGTERM, stop)
    # Own TERM before creating any child, including the interval inside Popen.
    # A stop arriving there is remembered and forwarded as soon as setup returns.
    master = slave = process = None
    try:
        master, slave = pty.openpty()
        settings = termios.tcgetattr(slave)
        settings[3] &= ~termios.ECHO
        termios.tcsetattr(slave, termios.TCSANOW, settings)
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 24, columns, 0, 0))
        env = {**env, 'TERM': 'xterm-256color', 'COLUMNS': str(columns), 'LINES': '24'}
        try:
            process = subprocess.Popen(argv, env=env, stdin=slave, stdout=slave, stderr=slave,
                                       start_new_session=True,
                                       preexec_fn=lambda: fcntl.ioctl(0, termios.TIOCSCTTY, 0))
        finally:
            os.close(slave)
            slave = None
        os.set_blocking(master, False)
    except BaseException:
        if process is not None:
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            process.wait()
        if master is not None: os.close(master)
        if slave is not None: os.close(slave)
        signal.signal(signal.SIGTERM, previous)
        raise
    decoder = codecs.getincrementaldecoder('utf-8')('replace')

    outgoing = bytearray()

    def write(data):
        if stopping_at is not None or len(outgoing) + len(data) > MAX_PENDING_INPUT:
            return False
        outgoing.extend(data)
        return True

    screen = OnboardingScreen(columns) if screen_output else None
    protocol = TerminalProtocol(write, screen=screen, login_link=login_link)
    last_screen = ''

    def write_input(data):
        return write(protocol.input_bytes(data))
    pending_input = b''
    output_open = True
    exited_at = None
    term_sent = kill_sent = False
    stdin_open = True
    stdin_registered = False

    def group_signal(value):
        # The leader remains an unreaped child until the final group cleanup.
        # Its PID cannot be recycled into an unrelated process group.
        try:
            os.killpg(process.pid, value)
        except ProcessLookupError:
            pass

    def exited():
        return os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is not None
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(master, selectors.EVENT_READ)
            while True:
                now = time.monotonic()
                if stopping_at is not None and not term_sent:
                    outgoing.clear()
                    group_signal(signal.SIGTERM)
                    term_sent = True
                if stopping_at is not None and now - stopping_at >= 2 and not kill_sent:
                    group_signal(signal.SIGKILL)
                    kill_sent = True
                if exited():
                    if exited_at is None:
                        exited_at = now
                    if not output_open or now - exited_at >= 0.5:
                        break
                if output_open:
                    selector.modify(master, selectors.EVENT_READ | (selectors.EVENT_WRITE if outgoing else 0))
                # Raw transport backpressure reaches the bounded Java queue. JSON
                # callers receive an explicit rejection instead of losing a reply.
                want_stdin = stdin_open and stopping_at is None and (json_input or len(outgoing) <= MAX_PENDING_INPUT - 8192)
                if want_stdin and not stdin_registered:
                    selector.register(sys.stdin, selectors.EVENT_READ)
                    stdin_registered = True
                elif not want_stdin and stdin_registered:
                    selector.unregister(sys.stdin)
                    stdin_registered = False
                for key, mask in selector.select(timeout=0.05):
                    if key.fileobj == master:
                        if mask & selectors.EVENT_WRITE and outgoing:
                            flush_pending(master, outgoing)
                        if not mask & selectors.EVENT_READ:
                            continue
                        try:
                            data = os.read(master, 8192)
                        except BlockingIOError:
                            continue
                        except OSError as error:
                            if error.errno != errno.EIO:
                                raise
                            data = b''
                        if not data:
                            selector.unregister(master)
                            output_open = False
                            continue
                        text = protocol.feed(decoder.decode(data))
                        if screen is not None:
                            snapshot = screen.snapshot()
                            if snapshot != last_screen:
                                screen_output(snapshot)
                                last_screen = snapshot
                        elif text:
                            output(text)
                    else:
                        data = os.read(sys.stdin.fileno(), 8192)
                        if not data:
                            selector.unregister(sys.stdin)
                            stdin_registered = False
                            stdin_open = False
                            stop()
                            continue
                        if not json_input:
                            write_input(data)
                            continue
                        pending_input += data
                        if len(pending_input) > 131072:
                            raise ValueError('입력이 너무 깁니다.')
                        while b'\n' in pending_input:
                            line, pending_input = pending_input.split(b'\n', 1)
                            request = json.loads(line)
                            accepted = write_input(str(request.get('text', '')).encode('utf-8'))
                            result = dict(ok=accepted, reason='queued' if accepted else 'input_queue_full')
                            if input_result:
                                input_result(result)
                            elif not accepted:
                                output('\nTerminal input queue is full.\n')
    finally:
        # Cleanup before wait() reaps the leader. Never signal a recycled PGID.
        group_signal(signal.SIGKILL)
        process.wait()
        os.close(master)
        signal.signal(signal.SIGTERM, previous)
    return process.returncode


if __name__ == '__main__':
    def show(text):
        sys.stdout.write(text)
        sys.stdout.flush()
    sys.exit(run_terminal(sys.argv[1:], dict(os.environ), show, json_input=False))
