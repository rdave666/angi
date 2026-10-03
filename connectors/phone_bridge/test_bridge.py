import contextlib
import io
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

import bridge

CONTROLLER = 'c' * 40
WORKER = 'w' * 40


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.store = bridge.Store(str(self.root / 'relay.sqlite3'))
        self.server = bridge.relay(self.store, CONTROLLER, WORKER, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = 'http://127.0.0.1:' + str(self.server.server_port)
        self.worker = bridge.Worker(self.url, WORKER, self.root, self.root / 'worker-state', allow_shell=True)
        self.addCleanup(self.tmp.cleanup)
        self.addCleanup(self.store.db.close)
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def submit(self, action='status', payload=None, timeout=60):
        return bridge.controller(self.url, CONTROLLER, 'submit',
                                 {'action': action, 'payload': payload or {}, 'timeout': timeout})['id']

    def result(self, ident):
        return bridge.controller(self.url, CONTROLLER, 'result', {'id': ident})

    def run_worker(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return self.worker.once()

    def test_status_and_phone_registration(self):
        self.assertFalse(bridge.controller(self.url, CONTROLLER, 'status')['connected_recently'])
        ident = self.submit()
        self.run_worker()
        result = self.result(ident)
        self.assertEqual(result['state'], 'completed')
        self.assertIn('shell', result['result']['actions'])
        self.assertTrue(bridge.controller(self.url, CONTROLLER, 'status')['connected_recently'])

    def test_roles_cannot_cross_endpoints(self):
        for path, auth, data in [('/status', WORKER, None), ('/worker/poll', CONTROLLER, {}),
                                 ('/jobs', WORKER, {'action': 'status'}), ('/status', 'bad', None)]:
            self.assertEqual(bridge.http(self.url + path, auth, data)[0], 401)

    def test_shell_executes_and_preserves_exit_status(self):
        ident = self.submit('shell', {'command': "printf 'hello'; printf 'bad' >&2; exit 7"})
        self.run_worker()
        result = self.result(ident)['result']
        self.assertEqual((result['stdout'], result['stderr'], result['exit_code']), ('hello', 'bad', 7))
        self.assertFalse(result['timed_out'])

    def test_commands_disabled_by_default(self):
        w = bridge.Worker(self.url, WORKER, self.root, self.root / 'disabled')
        with self.assertRaises(ValueError):
            w.execute({'action': 'shell', 'payload': {'command': 'true'}, 'timeout': 1})
        with self.assertRaises(ValueError):
            w.execute({'action': 'codex', 'payload': {'prompt': 'test'}, 'timeout': 1})

    def test_cwd_escape_including_symlink_is_rejected(self):
        (self.root / 'escape').symlink_to('/tmp', target_is_directory=True)
        for cwd in ('/tmp', '..', 'escape'):
            ident = self.submit('shell', {'command': 'pwd', 'cwd': cwd})
            self.run_worker()
            self.assertIn('error', self.result(ident)['result'])

    def test_timeout_kills_process_tree(self):
        ident = self.submit('shell', {'command': "sleep 20 & wait"}, 1)
        start = time.monotonic()
        self.run_worker()
        result = self.result(ident)['result']
        self.assertTrue(result['timed_out'])
        self.assertLess(time.monotonic() - start, 4)

    def test_output_is_bounded_and_does_not_deadlock(self):
        command = sys.executable + " -c 'import sys; sys.stdout.write(\"a\"*300000); sys.stderr.write(\"b\"*300000)'"
        ident = self.submit('shell', {'command': command})
        self.run_worker()
        result = self.result(ident)['result']
        self.assertEqual(len(result['stdout']), bridge.MAX_OUTPUT)
        self.assertEqual(len(result['stderr']), bridge.MAX_OUTPUT)
        self.assertTrue(result['truncated'])
        self.assertEqual(result['exit_code'], 0)

    def test_bridge_tokens_not_in_child_environment(self):
        with patch.dict(os.environ, {'ANGI_BRIDGE_WORKER_TOKEN': 'secret'}):
            result = self.worker.execute({'action': 'shell', 'payload': {'command': 'test -z "$ANGI_BRIDGE_WORKER_TOKEN"'}, 'timeout': 1})
        self.assertEqual(result['exit_code'], 0)

    def test_atomic_claim_prevents_second_delivery(self):
        ident = self.submit()
        first = self.store.poll({})
        self.assertEqual(first['job']['id'], ident)
        self.assertIsNone(self.store.poll({})['job'])
        self.assertEqual(self.result(ident)['state'], 'running')

    def test_expired_queued_jobs_are_not_executed(self):
        ident = self.submit()
        self.store.db.execute('UPDATE jobs SET expires=0 WHERE id=?', (ident,))
        self.store.db.commit()
        self.assertFalse(self.run_worker())
        self.assertEqual(self.result(ident)['state'], 'expired')

    def test_lost_execution_is_unknown_and_never_requeued(self):
        ident = self.submit()
        self.store.poll({})
        self.store.db.execute('UPDATE jobs SET expires=0 WHERE id=?', (ident,))
        self.store.db.commit()
        self.assertEqual(self.result(ident)['state'], 'unknown')
        self.assertIsNone(self.store.poll({})['job'])

    def test_interrupted_worker_journal_does_not_reexecute(self):
        ident = self.submit('shell', {'command': 'touch must-not-exist'})
        self.store.poll({})
        self.worker.save_record(self.worker.state / (ident + '.json'), {'id': ident})
        self.run_worker()
        self.assertIn('unknown', self.result(ident)['result']['error'])
        self.assertFalse((self.root / 'must-not-exist').exists())

    def test_result_retry_survives_worker_restart_without_duplicate_execution(self):
        ident = self.submit('shell', {'command': 'echo run >> runs.txt'})
        real_http = bridge.http
        def failing_delivery(url, *args, **kwargs):
            if '/worker/results/' in url:
                raise OSError('offline')
            return real_http(url, *args, **kwargs)
        with patch.object(bridge, 'http', side_effect=failing_delivery):
            with self.assertRaises(OSError):
                self.run_worker()
        restarted = bridge.Worker(self.url, WORKER, self.root, self.worker.state, allow_shell=True)
        restarted.deliver_pending()
        restarted.deliver_pending()
        self.assertEqual((self.root / 'runs.txt').read_text(), 'run\n')
        self.assertEqual(self.result(ident)['state'], 'completed')

    def test_persistent_relay_queue_survives_restart(self):
        ident = self.submit()
        reopened = bridge.Store(str(self.root / 'relay.sqlite3'))
        try:
            self.assertEqual(reopened.get(ident)['state'], 'queued')
        finally:
            reopened.db.close()

    def test_duplicate_results_are_idempotent(self):
        ident = self.submit()
        self.store.poll({})
        self.store.finish(ident, {'first': True})
        self.store.finish(ident, {'first': False})
        self.assertTrue(self.result(ident)['result']['first'])

    def test_reject_invalid_jobs(self):
        for body in [{'action': 'nope'}, {'action': 'status', 'timeout': True},
                     {'action': 'status', 'timeout': 601}, {'action': 'shell', 'payload': {}},
                     {'action': 'api', 'payload': {'path': 'http://evil'}},
                     {'action': 'api', 'payload': {'path': '/v1/chat/completions', 'method': 'POST', 'body': {'stream': True}}}]:
            self.assertEqual(bridge.http(self.url + '/jobs', CONTROLLER, body)[0], 400)

    def test_requires_https_except_loopback_and_rejects_credentials(self):
        for url in ('http://example.com', 'https://token@example.com', 'https://example.com/x', 'https://example.com?q=x'):
            with self.assertRaises(ValueError): bridge.base_url(url)
        self.assertEqual(bridge.base_url('https://example.com/'), 'https://example.com')
        with self.assertRaises(ValueError): bridge.base_url('https://example.com', local=True)

    def test_redirect_does_not_forward_authentication(self):
        class Redirect(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(302); self.send_header('Location', '/status'); self.end_headers()
            def log_message(self, *args): pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Redirect)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            self.assertEqual(bridge.http('http://127.0.0.1:' + str(server.server_port), CONTROLLER)[0], 302)
        finally:
            server.shutdown(); server.server_close()

    def test_local_api_forwarding_preserves_http_errors_and_json(self):
        class Api(BaseHTTPRequestHandler):
            def do_GET(self):
                data = json.dumps({'data': [], 'path': self.path}).encode()
                self.send_response(200); self.end_headers(); self.wfile.write(data)
            def do_POST(self):
                data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                self.send_response(503); self.end_headers(); self.wfile.write(json.dumps({'error': 'no model', 'request': data}).encode())
            def log_message(self, *args): pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Api)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.worker.api_url = 'http://127.0.0.1:' + str(server.server_port)
        try:
            ident = self.submit('api', {'path': '/v1/models'})
            self.run_worker()
            self.assertEqual(self.result(ident)['result']['body']['data'], [])
            ident = self.submit('api', {'path': '/v1/chat/completions', 'method': 'POST', 'body': {'messages': []}})
            self.run_worker()
            self.assertEqual(self.result(ident)['result']['http_status'], 503)
        finally:
            server.shutdown(); server.server_close()

    def test_codex_dispatch_uses_cli_sandbox_without_bypass(self):
        binary = self.root / 'codex'
        binary.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\ncat\n')
        binary.chmod(0o700)
        w = bridge.Worker(self.url, WORKER, self.root, self.root / 'codex-state', allow_codex=True)
        with patch.dict(os.environ, {'PATH': str(self.root) + os.pathsep + os.environ['PATH']}):
            result = w.execute({'action': 'codex', 'payload': {'prompt': 'Read the project'}, 'timeout': 2})
        self.assertEqual(result['exit_code'], 0)
        self.assertIn('workspace-write', result['stdout'])
        self.assertIn('Read the project', result['stdout'])
        self.assertNotIn('bypass', result['stdout'])

    def test_mcp_stdio_initialize_list_submit_and_result(self):
        messages = [{'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {}},
                    {'jsonrpc': '2.0', 'method': 'notifications/initialized'},
                    {'jsonrpc': '2.0', 'id': 2, 'method': 'tools/list'},
                    {'jsonrpc': '2.0', 'id': 3, 'method': 'tools/call', 'params': {'name': 'phone_submit', 'arguments': {'action': 'status'}}}]
        with patch.dict(os.environ, {'ANGI_BRIDGE_URL': self.url, 'ANGI_BRIDGE_CONTROLLER_TOKEN': CONTROLLER}):
            proc = subprocess.run([sys.executable, str(Path(bridge.__file__).resolve()), 'mcp'],
                                  input='\n'.join(json.dumps(m) for m in messages)+'\n', capture_output=True, text=True, timeout=5)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        replies = [json.loads(line) for line in proc.stdout.splitlines()]
        self.assertEqual(len(replies), 3)
        self.assertEqual(len(replies[1]['result']['tools']), 3)
        ident = json.loads(replies[2]['result']['content'][0]['text'])['id']
        self.run_worker()
        self.assertEqual(self.result(ident)['state'], 'completed')


if __name__ == '__main__':
    unittest.main()
