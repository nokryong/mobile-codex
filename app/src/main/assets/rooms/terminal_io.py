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


class TerminalProtocol:
    def __init__(self, reply):
        self.reply = reply
        self.pending = ''

    def feed(self, text):
        text = self.pending + text
        self.pending = ''
        output = []
        index = 0
        while index < len(text):
            char = text[index]
            if char != '\x1b':
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
                if command in replies:
                    self.reply(replies[command])
                index += 3 + end.start()
            elif kind in '()*+':
                if index + 2 == len(text):
                    self.pending = text[start:]
                    break
                index += 3
            else:
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


def run_terminal(argv, env, output, json_input=True, columns=80, input_result=None):
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

    protocol = TerminalProtocol(write)
    def write_input(data):
        # Raw CLI key handlers recognize CR as Enter; canonical terminals map
        # CR to LF themselves. Replies to terminal queries must remain unchanged.
        return write(data.replace(b'\r\n', b'\n').replace(b'\n', b'\r'))
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
                        if text:
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
