#!/usr/bin/env python3
"""Outbound ANGI phone bridge. Python 3.10+, standard library only."""
import argparse
import contextlib
import fcntl
import hmac
import json
import os
import platform
import shutil
from pathlib import Path
import secrets
import selectors
import signal
import sqlite3
import subprocess
import sys
import threading
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib import error, parse, request

MAX_BODY = 1048576
MAX_OUTPUT = 65536
ACTIONS = ('status', 'api', 'shell', 'codex')


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def base_url(value, local=False):
    u = parse.urlsplit(value)
    loopback = u.hostname in ('127.0.0.1', 'localhost', '::1')
    if u.username or u.password or u.query or u.fragment or u.path not in ('', '/'):
        raise ValueError('Use a base URL without credentials, path, query or fragment')
    if not u.hostname or not (u.scheme == 'https' or (u.scheme == 'http' and loopback)):
        raise ValueError('HTTPS is required except for loopback addresses')
    if local and not loopback:
        raise ValueError('The ANGI API must use a loopback address')
    return value.rstrip('/')


def token(name):
    value = os.environ.get(name, '')
    if len(value) < 32 or not value.isascii() or any(c.isspace() for c in value):
        raise ValueError(name + ' must contain at least 32 ASCII characters without spaces')
    return value


def http(url, auth=None, data=None, timeout=35, method=None):
    headers = {'Content-Type': 'application/json'}
    if auth:
        headers['Authorization'] = 'Bearer ' + auth
    req = request.Request(url, data=None if data is None else json.dumps(data).encode(),
                          headers=headers, method=method)
    try:
        response = request.build_opener(NoRedirect()).open(req, timeout=timeout)
    except error.HTTPError as exc:
        response = exc
    with response:
        raw = response.read(MAX_BODY + 1)
        if len(raw) > MAX_BODY:
            raise ValueError('Response exceeds size limit')
        try:
            body = json.loads(raw)
        except (ValueError, UnicodeError):
            body = {'error': 'Non-JSON response'}
        return response.status, body


def validate_job(body):
    action = body.get('action')
    payload = body.get('payload', {})
    timeout = body.get('timeout', 60)
    if action not in ACTIONS or not isinstance(payload, dict):
        raise ValueError('Invalid action or payload')
    if type(timeout) is not int or not 1 <= timeout <= 600:
        raise ValueError('timeout must be an integer between 1 and 600 seconds')
    if action in ('shell', 'codex'):
        field = 'command' if action == 'shell' else 'prompt'
        if not isinstance(payload.get(field), str) or not payload[field].strip() or len(payload[field]) > 16000:
            raise ValueError(field + ' must contain 1 to 16000 characters')
    if action == 'api':
        pair = (payload.get('method', 'GET'), payload.get('path'))
        if pair not in [('GET', '/health'), ('GET', '/v1/models'), ('POST', '/v1/chat/completions')]:
            raise ValueError('Unsupported API route')
        if pair[0] == 'POST':
            data = payload.get('body')
            if not isinstance(data, dict) or data.get('stream', False):
                raise ValueError('Use a JSON body with stream=false; SSE is not supported by this bridge')
    return {'action': action, 'payload': payload, 'timeout': timeout}


class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.lock = threading.Lock()
        self.db.execute('CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, job TEXT, '
                        'state TEXT, created REAL, expires REAL, started REAL, result TEXT)')
        self.db.execute('CREATE TABLE IF NOT EXISTS phone (id INTEGER PRIMARY KEY, seen REAL, info TEXT)')
        self.db.commit()

    def expire(self):
        now = time.time()
        self.db.execute("UPDATE jobs SET state='expired' WHERE state='queued' AND expires < ?", (now,))
        # Never requeue uncertain executions: a command may already have changed files.
        self.db.execute("UPDATE jobs SET state='unknown' WHERE state='running' AND expires < ?", (now,))

    def submit(self, job):
        with self.lock, self.db:
            self.expire()
            if self.db.execute("SELECT count(*) FROM jobs WHERE state IN ('queued','running')").fetchone()[0] >= 100:
                raise ValueError('Job queue is full')
            ident = secrets.token_hex(16)
            now = time.time()
            self.db.execute('INSERT INTO jobs VALUES (?,?,?,?,?,?,?)',
                            (ident, json.dumps(job), 'queued', now, now + 600, None, None))
            return {'id': ident, 'state': 'queued'}

    def poll(self, info):
        with self.lock, self.db:
            self.expire()
            now = time.time()
            self.db.execute('INSERT OR REPLACE INTO phone VALUES (1,?,?)', (now, json.dumps(info)))
            row = self.db.execute("SELECT id,job FROM jobs WHERE state='queued' ORDER BY created LIMIT 1").fetchone()
            if not row:
                return {'job': None}
            job = json.loads(row[1])
            self.db.execute("UPDATE jobs SET state='running',started=?,expires=? WHERE id=?",
                            (now, now + job['timeout'] + 120, row[0]))
            return {'job': dict(job, id=row[0])}

    def finish(self, ident, result):
        with self.lock, self.db:
            row = self.db.execute('SELECT state FROM jobs WHERE id=?', (ident,)).fetchone()
            if not row or row[0] not in ('running', 'unknown', 'completed'):
                raise ValueError('No running job with this ID')
            if row[0] != 'completed':
                self.db.execute("UPDATE jobs SET state='completed',result=? WHERE id=?", (json.dumps(result), ident))
            return {'accepted': True}

    def get(self, ident):
        with self.lock, self.db:
            self.expire()
            row = self.db.execute('SELECT state,result FROM jobs WHERE id=?', (ident,)).fetchone()
            if not row:
                return None
            return {'id': ident, 'state': row[0], 'result': json.loads(row[1]) if row[1] else None}

    def status(self):
        with self.lock:
            row = self.db.execute('SELECT seen,info FROM phone WHERE id=1').fetchone()
            return {'connected_recently': bool(row and time.time() - row[0] < 30),
                    'last_poll': row[0] if row else None, 'phone': json.loads(row[1]) if row else None}


def relay(store, controller_token, worker_token, host='127.0.0.1', port=8765):
    if len(controller_token) < 32 or len(worker_token) < 32 or controller_token == worker_token:
        raise ValueError('Use distinct controller and worker tokens of at least 32 characters')

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Do not log job prompts, tokens or output.

        def respond(self, status, body):
            raw = json.dumps(body).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(raw)

        def route(self):
            self.connection.settimeout(10)
            path = parse.urlsplit(self.path).path
            expected = worker_token if path.startswith('/worker/') else controller_token
            if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + expected):
                return self.respond(401, {'error': 'Unauthorized'})
            body = {}
            if self.command == 'POST':
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 < size <= MAX_BODY:
                    return self.respond(413, {'error': 'Invalid body size'})
                raw = self.rfile.read(size)
                if len(raw) != size:
                    raise ValueError('Incomplete request body')
                body = json.loads(raw)
                if not isinstance(body, dict):
                    raise ValueError('Expected JSON object')
            if self.command == 'GET' and path == '/status':
                return self.respond(200, store.status())
            if self.command == 'POST' and path == '/jobs':
                return self.respond(201, store.submit(validate_job(body)))
            if self.command == 'GET' and path.startswith('/jobs/'):
                job = store.get(path.rsplit('/', 1)[-1])
                return self.respond(200 if job else 404, job or {'error': 'Unknown job'})
            if self.command == 'POST' and path == '/worker/poll':
                return self.respond(200, store.poll(body))
            if self.command == 'POST' and path.startswith('/worker/results/'):
                return self.respond(200, store.finish(path.rsplit('/', 1)[-1], body))
            self.respond(404, {'error': 'Unknown route'})

        def do_GET(self):
            try:
                self.route()
            except (ValueError, UnicodeError):
                self.respond(400, {'error': 'Invalid request'})
            except (OSError, TimeoutError):
                pass

        do_POST = do_GET

    return ThreadingHTTPServer((host, port), Handler)


def run_process(argv, cwd, timeout, input_text=None):
    env = {k: v for k, v in os.environ.items() if not k.startswith('ANGI_BRIDGE_')}
    with tempfile.TemporaryFile() as stdin_file:
        if input_text:
            stdin_file.write(input_text.encode())
            stdin_file.seek(0)
        process = subprocess.Popen(argv, cwd=cwd, env=env, stdin=stdin_file if input_text else subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    outputs = {'stdout': bytearray(), 'stderr': bytearray()}
    totals = {'stdout': 0, 'stderr': 0}
    deadline = time.monotonic() + timeout
    timed_out = False
    try:
        with selectors.DefaultSelector() as sel:
            for name in outputs:
                sel.register(getattr(process, name), selectors.EVENT_READ, name)
            while sel.get_map():
                if time.monotonic() >= deadline:
                    timed_out = True
                    break
                for key, _ in sel.select(min(0.2, max(0, deadline - time.monotonic()))):
                    chunk = os.read(key.fileobj.fileno(), 8192)
                    if not chunk:
                        sel.unregister(key.fileobj)
                    else:
                        name = key.data
                        totals[name] += len(chunk)
                        outputs[name].extend(chunk[:max(0, MAX_OUTPUT - len(outputs[name]))])
            if not timed_out:
                try:
                    process.wait(timeout=max(0.01, deadline - time.monotonic()))
                except subprocess.TimeoutExpired:
                    timed_out = True
    finally:
        with contextlib.suppress(ProcessLookupError):
            os.killpg(process.pid, signal.SIGKILL)
        process.wait()
        process.stdout.close()
        process.stderr.close()
    return {**{k: bytes(v).decode(errors='replace') for k, v in outputs.items()},
            'exit_code': process.returncode, 'timed_out': timed_out,
            'truncated': any(totals[k] > MAX_OUTPUT for k in totals)}


class Worker:
    def __init__(self, url, auth, workspace, state, allow_shell=False, allow_codex=False,
                 api_url='http://127.0.0.1:8080'):
        self.url = base_url(url)
        self.auth = auth
        self.workspace = Path(workspace).resolve(strict=True)
        self.state = Path(state)
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.allow_shell = allow_shell
        self.allow_codex = allow_codex
        self.api_url = base_url(api_url, local=True)

    def info(self):
        return {'platform': sys.platform, 'architecture': platform.machine(),
                'python_version': platform.python_version(), 'codex_available': shutil.which('codex') is not None,
                'workspace': str(self.workspace),
                'actions': ['status', 'api'] + (['shell'] if self.allow_shell else []) + (['codex'] if self.allow_codex else [])}

    def execute(self, job):
        validate_job(job)
        action, payload, timeout = job['action'], job['payload'], job['timeout']
        if action == 'status':
            return self.info()
        if action == 'api':
            code, body = http(self.api_url + payload['path'], os.environ.get('ANGI_API_TOKEN'),
                              payload.get('body') if payload.get('method', 'GET') == 'POST' else None,
                              timeout=timeout, method=payload.get('method', 'GET'))
            return {'http_status': code, 'body': body}
        allowed = self.allow_shell if action == 'shell' else self.allow_codex
        if not allowed:
            raise ValueError(action + ' execution is disabled on this phone')
        cwd = (self.workspace / payload.get('cwd', '.')).resolve(strict=True)
        if not cwd.is_relative_to(self.workspace) or not cwd.is_dir():
            raise ValueError('cwd must be a directory inside the configured workspace')
        if action == 'shell':
            return run_process(['bash', '-c', payload['command']], cwd, timeout)
        # Separate noninteractive task; keep Codex's sandbox and approval controls.
        return run_process(['codex', 'exec', '--sandbox', 'workspace-write', '-'], cwd, timeout, payload['prompt'])

    def deliver_pending(self):
        for path in self.state.glob('*.json'):
            record = json.loads(path.read_text())
            result = record.get('result', {'error': 'Worker interrupted; execution outcome unknown. Not retried.'})
            code, _ = http(self.url + '/worker/results/' + record['id'], self.auth, result)
            if code != 200:
                raise RuntimeError('Relay rejected result: HTTP ' + str(code))
            path.unlink()

    def once(self):
        self.deliver_pending()
        code, body = http(self.url + '/worker/poll', self.auth, self.info())
        if code != 200:
            raise RuntimeError('Relay rejected polling: HTTP ' + str(code))
        print(json.dumps({'event': 'connected'}), flush=True)
        job = body.get('job')
        if not job:
            return False
        ident = job['id']
        if len(ident) != 32 or any(c not in '0123456789abcdef' for c in ident):
            raise ValueError('Invalid job ID')
        path = self.state / (ident + '.json')
        self.save_record(path, {'id': ident})  # Journal before execution; never blindly replay.
        print(json.dumps({'event': 'executing'}), flush=True)
        try:
            result = self.execute(job)
        except Exception as exc:
            result = {'error': str(exc)}
        self.save_record(path, {'id': ident, 'result': result})
        self.deliver_pending()
        print(json.dumps({'event': 'connected'}), flush=True)
        return True

    @staticmethod
    def save_record(path, record):
        tmp = path.with_suffix('.tmp')
        with open(tmp, 'w') as f:
            json.dump(record, f)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)

    def run(self):
        with open(self.state / 'worker.lock', 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            delay = 1
            while True:
                try:
                    active = self.once()
                    delay = 1
                    if not active:
                        time.sleep(3)
                except (OSError, ValueError, RuntimeError) as exc:
                    print(json.dumps({'event': 'disconnected'}), flush=True)
                    print('Bridge disconnected; retrying (' + type(exc).__name__ + ')', file=sys.stderr)
                    time.sleep(delay)
                    delay = min(delay * 2, 30)


TOOLS = [
    {'name': 'phone_status', 'description': 'Phone connection status and enabled actions.', 'inputSchema': {'type': 'object', 'properties': {}, 'additionalProperties': False}},
    {'name': 'phone_submit', 'description': 'Queue a phone job. Shell and Codex require explicit enablement on the phone. Read the result with phone_result.',
     'inputSchema': {'type': 'object', 'properties': {'action': {'type': 'string', 'enum': list(ACTIONS)}, 'payload': {'type': 'object'}, 'timeout': {'type': 'integer', 'minimum': 1, 'maximum': 600}}, 'required': ['action']}},
    {'name': 'phone_result', 'description': 'Read a job result; unknown outcomes must not be blindly resubmitted.', 'inputSchema': {'type': 'object', 'properties': {'id': {'type': 'string'}}, 'required': ['id']}},
]


def controller(url, auth, operation, body=None):
    endpoint = '/status' if operation == 'status' else '/jobs' if operation == 'submit' else '/jobs/' + parse.quote(body['id'], safe='')
    code, result = http(base_url(url) + endpoint, auth, body if operation == 'submit' else None)
    if code not in (200, 201):
        raise RuntimeError('Relay request failed: HTTP ' + str(code))
    return result


def mcp(url, auth):
    for line in sys.stdin:
        msg = None
        try:
            msg = json.loads(line)
            if 'id' not in msg:
                continue
            method = msg.get('method')
            if method == 'initialize':
                result = {'protocolVersion': '2024-11-05', 'capabilities': {'tools': {}}, 'serverInfo': {'name': 'angi-phone-bridge', 'version': '0.1.0'}}
            elif method == 'ping':
                result = {}
            elif method == 'tools/list':
                result = {'tools': TOOLS}
            elif method == 'tools/call':
                params = msg['params']
                name = params['name']
                args = params.get('arguments', {})
                op = {'phone_status': 'status', 'phone_submit': 'submit', 'phone_result': 'result'}[name]
                try:
                    data = controller(url, auth, op, args)
                    result = {'content': [{'type': 'text', 'text': json.dumps(data)}]}
                except (OSError, ValueError, RuntimeError) as exc:
                    result = {'isError': True, 'content': [{'type': 'text', 'text': str(exc)}]}
            else:
                raise ValueError('Unsupported method')
            response = {'jsonrpc': '2.0', 'id': msg['id'], 'result': result}
        except (ValueError, TypeError, KeyError) as exc:
            response = {'jsonrpc': '2.0', 'id': msg.get('id') if isinstance(msg, dict) else None,
                        'error': {'code': -32602, 'message': str(exc)}}
        print(json.dumps(response), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    r = sub.add_parser('relay')
    r.add_argument('--host', default='127.0.0.1')
    r.add_argument('--port', type=int, default=8765)
    r.add_argument('--state', required=True)
    w = sub.add_parser('worker')
    w.add_argument('--workspace', required=True)
    w.add_argument('--state', required=True)
    w.add_argument('--allow-shell', action='store_true')
    w.add_argument('--allow-codex', action='store_true')
    w.add_argument('--api-url', default='http://127.0.0.1:8080')
    c = sub.add_parser('client')
    c.add_argument('operation', choices=['status', 'submit', 'result'])
    c.add_argument('--job', help='JSON job file for submit')
    c.add_argument('--id', help='Job ID for result')
    sub.add_parser('mcp')
    args = parser.parse_args()
    os.umask(0o077)
    if args.mode == 'relay':
        Path(args.state).mkdir(parents=True, exist_ok=True)
        relay(Store(str(Path(args.state) / 'relay.sqlite3')), token('ANGI_BRIDGE_CONTROLLER_TOKEN'),
              token('ANGI_BRIDGE_WORKER_TOKEN'), args.host, args.port).serve_forever()
    else:
        url = os.environ.get('ANGI_BRIDGE_URL', '')
        if args.mode == 'worker':
            signal.signal(signal.SIGTERM, lambda signum, frame: sys.exit(0))
            Worker(url, token('ANGI_BRIDGE_WORKER_TOKEN'), args.workspace, args.state,
                   args.allow_shell, args.allow_codex, args.api_url).run()
        elif args.mode == 'mcp':
            mcp(url, token('ANGI_BRIDGE_CONTROLLER_TOKEN'))
        else:
            body = json.loads(Path(args.job).read_text()) if args.job else {'id': args.id} if args.id else None
            if args.operation != 'status' and body is None:
                parser.error('submit requires --job; result requires --id')
            print(json.dumps(controller(url, token('ANGI_BRIDGE_CONTROLLER_TOKEN'), args.operation, body), indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError) as exc:
        sys.exit(str(exc))
