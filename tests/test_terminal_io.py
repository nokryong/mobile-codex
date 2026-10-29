import importlib.util
import os
import errno
import json
import signal
import threading
import time
import hashlib
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

file = Path(__file__).parents[1] / 'app/src/main/assets/rooms/terminal_io.py'
spec = importlib.util.spec_from_file_location('terminal_io', file)
terminal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(terminal)


class TerminalProtocolTests(unittest.TestCase):
    def test_partial_write_and_eagain_preserve_exact_unwritten_bytes(self):
        pending = bytearray(b'abcdef')
        with patch.object(terminal.os, 'write', return_value=2): terminal.flush_pending(7, pending)
        self.assertEqual(pending, b'cdef')
        with patch.object(terminal.os, 'write', side_effect=BlockingIOError(errno.EAGAIN, 'full')):
            terminal.flush_pending(7, pending)
        self.assertEqual(pending, b'cdef')
        with patch.object(terminal.os, 'write', return_value=4): terminal.flush_pending(7, pending)
        self.assertEqual(pending, b'')

    def test_background_query_receives_reply_instead_of_hanging_login(self):
        for ending in ['\x07', '\x1b\\']:
            query = '\x1b]11;?' + ending
            for split in range(len(query) + 1):
                replies = []
                parser = terminal.TerminalProtocol(replies.append)
                text = parser.feed(query[:split]) + parser.feed(query[split:] + 'Login URL')
                self.assertEqual(text, 'Login URL')
                self.assertEqual(replies, [('\x1b]11;rgb:0000/0000/0000' + ending).encode()])

    def test_colors_cursor_and_device_queries_are_answered_without_exposing_controls(self):
        replies = []
        parser = terminal.TerminalProtocol(replies.append)
        self.assertEqual(parser.feed('Before\x1b]10;?;?\x07\x1b[6n\x1b[c\x1b[18tAfter'), 'BeforeAfter')
        self.assertEqual(replies, [b'\x1b]10;rgb:ffff/ffff/ffff\x07', b'\x1b]11;rgb:0000/0000/0000\x07',
                                  b'\x1b[1;1R', b'\x1b[?1;2c', b'\x1b[8;24;80t'])

    def test_chunked_sequences_keep_plain_urls_and_korean_and_ignore_clipboard_requests(self):
        replies = []
        parser = terminal.TerminalProtocol(replies.append)
        chunks = ['\x1b[3', '1m로그인\x1b[0m https://claude.ai/auth\n', '\x1b]52;c;secret', '\x07다음']
        self.assertEqual(''.join(parser.feed(chunk) for chunk in chunks), '로그인 https://claude.ai/auth\n다음')
        self.assertEqual(replies, [])

    def test_incomplete_control_sequence_is_bounded(self):
        parser = terminal.TerminalProtocol(lambda _: None)
        self.assertEqual(parser.feed('\x1b]' + 'a' * 9000), '')
        self.assertEqual(parser.pending, '')
        self.assertEqual(parser.feed('Ready'), 'Ready')

    @unittest.skipUnless(os.name == 'posix', 'Actual PTY and controlling terminal require POSIX')
    def test_real_cli_waits_for_color_response_and_accepts_code_with_raw_enter(self):
        child = r'''
import os,tty,select
tty.setraw(0)
os.write(1,b'\x1b]11;?\x07')
assert select.select([0],[],[],3)[0], 'Missing terminal color reply'
reply=os.read(0,200)
assert reply==b'\x1b]11;rgb:0000/0000/0000\x07',repr(reply)
os.write(1,b'Paste code: ')
value=b''
while not value.endswith(b'\r'):
 assert select.select([0],[],[],3)[0], 'Missing Enter'
 value+=os.read(0,1)
assert value==b'fixture-code\r',repr(value)
os.write(1,b'AUTH_INPUT_ACCEPTED\n')
'''
        process = subprocess.Popen([sys.executable, str(file), sys.executable, '-u', '-c', child],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            self.assertEqual(process.stdout.read(len(b'Paste code: ')), b'Paste code: ')
            process.stdin.write(b'fixture-code\n'); process.stdin.flush()
            self.assertEqual(process.wait(timeout=7), 0)
            text = process.stdout.read()
            self.assertIn(b'AUTH_INPUT_ACCEPTED', text)
            self.assertNotIn(b'fixture-code', text)
        finally:
            if process.poll() is None:
                process.kill(); process.wait()
            for pipe in [process.stdin, process.stdout, process.stderr]:
                pipe.close()


@unittest.skipUnless(os.name == 'posix', 'Actual PTY process groups require POSIX')
class TerminalBackpressureTests(unittest.TestCase):
    def launch(self, child):
        process = subprocess.Popen([sys.executable, str(file), sys.executable, '-u', '-c', child],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.addCleanup(self.cleanup, process)
        return process

    def cleanup(self, process):
        if process.poll() is None:
            process.terminate()
            try: process.wait(timeout=4)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=2)
        for stream in (process.stdin, process.stdout, process.stderr):
            if stream: stream.close()

    def alive(self, pid):
        try:
            status = Path(f'/proc/{pid}/stat')
            if status.exists() and status.read_text().split(') ', 1)[1].startswith('Z'):
                return False
            os.kill(pid, 0); return True
        except ProcessLookupError:
            return False

    def own_group(self, pids):
        # Also clean up on assertion/timeout failures. Check process start times
        # before signalling so test cleanup cannot target a recycled PID/PGID.
        def identity(pid):
            try: return Path(f'/proc/{pid}/stat').read_text().split(') ',1)[1].split()[19]
            except (FileNotFoundError, ProcessLookupError): return None
        identities={pid:identity(pid) for pid in pids}
        def cleanup():
            for pid, started in identities.items():
                if started is not None and identity(pid)==started:
                    try:
                        if os.getpgid(pid)==pids[0]: os.killpg(pids[0],signal.SIGKILL);return
                    except ProcessLookupError: pass
        self.addCleanup(cleanup)

    def test_full_pipe_term_ignored_large_output_duplicate_stop_and_group_cleanup(self):
        outsider = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(30)'])
        self.addCleanup(lambda: (outsider.terminate(), outsider.wait(timeout=3)))
        process = self.launch('''import os,signal,time,tty,subprocess,sys
tty.setraw(0)
signal.signal(signal.SIGTERM,signal.SIG_IGN)
child=subprocess.Popen([sys.executable,'-c','import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(30)'])
os.write(1,('READY:%s:%s\\n'%(os.getpid(),child.pid)).encode())
while True: os.write(1,b'x'*8192)
''')
        ready = process.stdout.readline().decode().strip().split(':')
        pids = [int(value) for value in ready[1:]]
        self.own_group(pids)
        drained = threading.Thread(target=lambda: process.stdout.read(), daemon=True); drained.start()
        def fill():
            try:
                for _ in range(128): process.stdin.write(b'a'*16000); process.stdin.flush()
            except (BrokenPipeError, OSError, ValueError): pass
        writer = threading.Thread(target=fill, daemon=True); writer.start()
        time.sleep(.2)
        self.assertTrue(writer.is_alive(), 'fixture must actually fill the unread input pipe')
        began = time.monotonic(); process.terminate(); time.sleep(.1); process.terminate()
        process.wait(timeout=3.5)
        self.assertLess(time.monotonic()-began, 3.2)
        writer.join(2); drained.join(2)
        self.assertFalse(writer.is_alive()); self.assertFalse(drained.is_alive())
        self.assertTrue(outsider.poll() is None)
        deadline = time.monotonic()+2
        while any(self.alive(pid) for pid in pids) and time.monotonic()<deadline: time.sleep(.02)
        self.assertFalse(any(self.alive(pid) for pid in pids))

    def test_partial_writes_deliver_all_bytes_and_eof_reaps_owned_process(self):
        data = b'a'*250000
        process = self.launch('''import os,tty,time,hashlib
tty.setraw(0);os.write(1,b'READY\\n');time.sleep(.2)
data=b''
while len(data)<250000: data+=os.read(0,min(1024,250000-len(data)))
os.write(1,hashlib.sha256(data).hexdigest().encode()+b'\\n')
''')
        self.assertEqual(process.stdout.readline(),b'READY\n')
        def fill():
            try: process.stdin.write(data);process.stdin.flush()
            except (BrokenPipeError,OSError): pass
        writer=threading.Thread(target=fill,daemon=True);writer.start()
        self.assertEqual(process.stdout.readline().strip().decode(),hashlib.sha256(data).hexdigest())
        process.wait(timeout=3);writer.join(2);self.assertFalse(writer.is_alive())
        eof=self.launch('import os,time,tty;tty.setraw(0);os.write(1,b"READY\\n");time.sleep(30)')
        self.assertEqual(eof.stdout.readline(),b'READY\n');eof.stdin.close();eof.wait(timeout=3)

    def test_json_queue_overflow_returns_an_explicit_result(self):
        wrapper = '''import sys,os,json
sys.path.insert(0,sys.argv[1])
from terminal_io import run_terminal
run_terminal([sys.executable,'-c','import tty,time;tty.setraw(0);time.sleep(30)'],dict(os.environ),lambda text:None,
 input_result=lambda result:print(json.dumps(result),flush=True))
'''
        process=subprocess.Popen([sys.executable,'-c',wrapper,str(file.parent)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        self.addCleanup(self.cleanup,process)
        data=(json.dumps(dict(text='x'*16000))+'\n').encode()*64
        output,_=process.communicate(data,timeout=5)
        results=[json.loads(line) for line in output.splitlines()]
        self.assertTrue(any(result.get('reason')=='input_queue_full' and not result['ok'] for result in results))

    def test_stop_during_popen_cannot_orphan_a_new_child(self):
        wrapper = '''import sys,os,signal,subprocess
sys.path.insert(0,sys.argv[1])
import terminal_io
original=subprocess.Popen
def launch(*args,**kwargs):
 child=original(*args,**kwargs)
 print(child.pid,flush=True)
 os.kill(os.getpid(),signal.SIGTERM)
 return child
terminal_io.subprocess.Popen=launch
terminal_io.run_terminal([sys.executable,'-c','import time;time.sleep(30)'],dict(os.environ),lambda text:None,json_input=False)
'''
        process=subprocess.Popen([sys.executable,'-c',wrapper,str(file.parent)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        self.addCleanup(self.cleanup,process)
        output,error=process.communicate(timeout=5)
        if output.strip(): self.own_group([int(output.strip())])
        self.assertEqual(process.returncode,0,error)
        self.assertFalse(self.alive(int(output.strip())))

    @unittest.skipUnless(Path('/proc/self/fd').exists(), 'Linux descriptor accounting')
    def test_repeated_exit_does_not_leak_descriptors(self):
        before=len(list(Path('/proc/self/fd').iterdir()))
        for _ in range(5):
            process=self.launch('print("done")');process.communicate(timeout=3);self.cleanup(process)
        self.assertLessEqual(len(list(Path('/proc/self/fd').iterdir())),before)


if __name__ == '__main__':
    unittest.main()
