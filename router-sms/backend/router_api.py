#!/usr/bin/env python3
"""LAN-only ModemManager bridge. Python 3.8+, no pip dependencies."""
import hmac
import ipaddress
import json
import os
import re
import subprocess
import socket
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler

LOCK = threading.Lock()
CONFIG = {}
PREFIX = '/org/freedesktop/ModemManager1/'

class ApiError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message

def mm(*args, structured=False):
    try:
        p = subprocess.run(['/usr/bin/mmcli', '--timeout=15', *args,
                            *(['--output-json'] if structured else [])],
                           capture_output=True, encoding='utf-8', errors='replace', timeout=20,
                           env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LC_ALL': 'C.UTF-8'})
    except subprocess.TimeoutExpired:
        raise ApiError(504, 'ModemManager timeout; outcome may be unknown. Refresh before retrying.')
    if p.returncode:
        raise ApiError(502, p.stderr.strip()[:500] or 'mmcli failed')
    if structured:
        try:
            return json.loads(p.stdout)
        except ValueError:
            raise ApiError(502, 'mmcli JSON output invalid')
    return p.stdout

def paths(value, kind):
    found = set()
    def visit(v):
        if isinstance(v, dict):
            for x in v.values(): visit(x)
        elif isinstance(v, list):
            for x in v: visit(x)
        elif isinstance(v, str):
            found.update(re.findall(re.escape(PREFIX + kind + '/') + r'\d+\b', v))
    visit(value)
    return sorted(found, key=lambda p: int(p.rsplit('/', 1)[1]))

def number(value):
    if not isinstance(value, str) or not re.fullmatch(r'\+?[0-9]{3,20}', value):
        raise ApiError(400, 'Number must contain 3–20 digits, optionally starting with +')
    return value

def properties_text(value):
    # mmcli has its own key/value parser: shell=False alone is insufficient.
    # Use a quote delimiter absent from the message. Reject mixed quotes/backslashes
    # rather than relying on undocumented escaping in older GLib/mmcli builds.
    if not isinstance(value, str) or not value.strip() or len(value) > 1600:
        raise ApiError(400, 'SMS text must contain 1–1600 characters')
    if '\\' in value or any(ord(c) < 32 and c not in '\n\r\t' for c in value):
        raise ApiError(400, 'Backslashes and control characters are unsupported')
    quote = '"' if '"' not in value else "'"
    if quote in value:
        raise ApiError(400, 'Use only one style of quote in SMS text')
    return quote + value + quote

def listed(kind):
    flag = '--messaging-list-sms' if kind == 'SMS' else '--voice-list-calls'
    return paths(mm('-m', CONFIG['modem'], flag, structured=True), kind)

def voice():
    try:
        calls = listed('Call')
        return {'interface_available': True, 'controls_enabled': CONFIG.get('enable_voice', False),
                'audio_supported': False, 'calls': calls,
                'note': 'Call control only. ModemManager Voice interface does not prove VoLTE or audio support.'}
    except ApiError as e:
        return {'interface_available': False, 'controls_enabled': False,
                'audio_supported': False, 'calls': [], 'note': e.message}

def active_calls():
    return [p for p in listed('Call') if mm('-o', p, structured=True).get('call', {}).get('properties', {}).get('state') != 'terminated']

def dispatch(method, path, body):
    # Local label only: independent of modem discovery and SIM provisioning.
    if path == '/v1/settings/number':
        if method == 'GET': return {'own_number': CONFIG.get('own_number', '')}
        if method == 'POST':
            value = body.get('own_number')
            if not isinstance(value, str): raise ApiError(400, 'own_number must be a string')
            value = value.strip()
            if value: number(value)
            import router_setup
            updated = dict(CONFIG, own_number=value)
            router_setup.save_config(updated)
            CONFIG.update(updated)
            return {'ok': True, 'own_number': value}
    if path.startswith('/v1/device/'):
        import router_setup
        if method == 'GET' and path == '/v1/device/status': return router_setup.inspect(CONFIG)
        if method == 'POST' and path == '/v1/device/setup':
            try:
                if active_calls(): raise ApiError(409, '请先结束当前呼叫，再进行设备适配')
            except ApiError as e:
                if e.status == 409: raise
            try: return router_setup.apply(CONFIG)
            except RuntimeError as e: raise ApiError(409, str(e))
        raise ApiError(404, 'Unknown device endpoint')
    if CONFIG.get('hardware_profile') == 'mg1300-dji-gen1':
        import router_setup
        try: CONFIG['modem'] = router_setup.resolve_modem()
        except RuntimeError as e: raise ApiError(409, str(e))
    if method == 'GET' and path == '/v1/status':
        return {'modem': mm('-m', CONFIG['modem'], structured=True), 'voice': voice(), 'own_number': CONFIG.get('own_number', '')}
    if method == 'GET' and path == '/v1/sms':
        ids = listed('SMS')
        if body.get('order') == 'newest': ids.reverse()
        offset = body.get('offset', 0)
        result, errors = [], []
        limit = body.get('limit', 25)
        for p in ids[offset:offset + limit]:
            try: result.append({'id': p.rsplit('/', 1)[1], 'data': mm('-s', p, structured=True)})
            except ApiError as e: errors.append({'id': p, 'error': e.message})
        return {'messages': result, 'errors': errors, 'next': offset + limit if offset + limit < len(ids) else None}
    if method == 'POST' and path == '/v1/sms/delete':
        sms_id = body.get('id')
        if not isinstance(sms_id, str) or not re.fullmatch(r'\d{1,10}', sms_id): raise ApiError(400, 'Invalid SMS ID')
        sms = PREFIX + 'SMS/' + sms_id
        if sms not in listed('SMS'): raise ApiError(404, 'SMS does not belong to configured modem')
        mm('-m', CONFIG['modem'], '--messaging-delete-sms=' + sms)
        return {'ok': True}
    if method == 'POST' and path == '/v1/settings/voice':
        enabled = body.get('enabled')
        if type(enabled) is not bool: raise ApiError(400, 'enabled must be boolean')
        if active_calls(): raise ApiError(409, '请先结束当前呼叫')
        if enabled and not voice()['interface_available']: raise ApiError(409, 'Voice interface unavailable')
        import router_setup
        updated = dict(CONFIG, enable_voice=enabled)
        router_setup.save_config(updated)
        CONFIG.update(updated)
        return {'ok': True, 'enabled': enabled}
    if method == 'POST' and path == '/v1/sms/send':
        n, t = number(body.get('number')), properties_text(body.get('text'))
        out = mm('-m', CONFIG['modem'], '--messaging-create-sms=number=' + n + ',text=' + t)
        ids = paths(out, 'SMS')
        if len(ids) != 1: raise ApiError(502, 'SMS created but ID unknown; inspect mmcli before retrying')
        try: mm('-s', ids[0], '--send')
        except ApiError as e:
            raise ApiError(e.status, e.message + ' Draft ID: ' + ids[0] + '; do not retry blindly.')
        return {'id': ids[0], 'state': 'submitted', 'note': 'Submission is not delivery confirmation'}
    if method == 'GET' and path == '/v1/calls':
        v = voice()
        v['details'] = [{'id': p.rsplit('/', 1)[1], 'data': mm('-o', p, structured=True)} for p in v['calls']]
        return v
    if method == 'POST' and path.startswith('/v1/calls/'):
        v = voice()
        if not v['controls_enabled']: raise ApiError(409, 'Voice unavailable or disabled by router administrator')
        action = path.removeprefix('/v1/calls/') if hasattr(str, 'removeprefix') else path[len('/v1/calls/'):]
        if action == 'dial':
            if active_calls(): raise ApiError(409, '已有未结束呼叫，请先挂断再拨号')
            out = mm('-m', CONFIG['modem'], '--voice-create-call=number=' + number(body.get('number')))
            ids = paths(out, 'Call')
            if len(ids) != 1: raise ApiError(502, 'Call created, ID unknown; refresh before retry')
            mm('-o', ids[0], '--start')
            return {'id': ids[0]}
        if action not in ('accept', 'hangup'): raise ApiError(404, 'Unknown call action')
        call_id = body.get('id')
        if not isinstance(call_id, str) or not re.fullmatch(r'\d{1,10}', call_id): raise ApiError(400, 'Invalid call ID')
        p = PREFIX + 'Call/' + call_id
        if p not in v['calls']: raise ApiError(404, 'Call does not belong to configured modem')
        mm('-o', p, '--' + action)
        return {'ok': True}
    raise ApiError(404, 'Unknown endpoint')

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *args): pass  # No tokens, SMS or phone numbers in access logs.
    def setup(self):
        super().setup()
        self.connection.settimeout(10)
    def do_GET(self): self.handle_api()
    def do_POST(self): self.handle_api()
    def handle_api(self):
        from urllib.parse import urlsplit, parse_qs
        try:
            if ipaddress.ip_address(self.client_address[0]) not in ipaddress.ip_network(CONFIG['lan_cidr']):
                raise ApiError(403, 'LAN only')
            if not hmac.compare_digest(self.headers.get('Authorization', '').encode('utf-8'), ('Bearer ' + CONFIG['token']).encode('ascii')):
                raise ApiError(401, 'Unauthorized')
            if self.headers.get('Origin') or self.headers.get('Transfer-Encoding'):
                raise ApiError(403, 'Browser and chunked requests are disabled')
            try: length = int(self.headers.get('Content-Length', '0'))
            except ValueError: raise ApiError(400, 'Invalid content length')
            if not 0 <= length <= 16384: raise ApiError(413, 'Body too large')
            parsed = urlsplit(self.path)
            body = {}
            if self.command == 'POST':
                if self.headers.get_content_type() != 'application/json': raise ApiError(415, 'JSON required')
                try: body = json.loads(self.rfile.read(length))
                except (ValueError, UnicodeError): raise ApiError(400, 'Invalid JSON')
                if not isinstance(body, dict): raise ApiError(400, 'JSON object required')
            elif parsed.path == '/v1/sms':
                try: body['offset'] = int(parse_qs(parsed.query).get('offset', ['0'])[0])
                except ValueError: raise ApiError(400, 'Invalid offset')
                if not 0 <= body['offset'] <= 10000: raise ApiError(400, 'Invalid offset')
                try: body['limit'] = int(parse_qs(parsed.query).get('limit', ['25'])[0])
                except ValueError: raise ApiError(400, 'Invalid limit')
                if not 1 <= body['limit'] <= 25: raise ApiError(400, 'Invalid limit')
                body['order'] = parse_qs(parsed.query).get('order', ['oldest'])[0]
                if body['order'] not in ('oldest', 'newest'): raise ApiError(400, 'Invalid order')
            with LOCK: result = dispatch(self.command, parsed.path, body)
            self.reply(200, result)
        except ApiError as e: self.reply(e.status, {'error': e.message})
        except (OSError, ValueError): self.reply(503, {'error': 'Backend unavailable; inspect service and config'})
    def reply(self, status, value):
        raw = json.dumps(value, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(raw)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.close_connection = True
        self.wfile.write(raw)

def main():
    global CONFIG
    with open('/etc/router-sms.json', encoding='utf-8') as f: CONFIG = json.load(f)
    if not re.fullmatch(r'[a-f0-9]{64}', CONFIG['token']): raise ValueError('Token must be 32 random bytes as hex')
    if CONFIG['bind'] != '192.168.8.1' or CONFIG['lan_cidr'] != '192.168.8.0/24':
        raise ValueError('This deployment only allows 192.168.8.1 / 192.168.8.0/24')
    if not re.fullmatch(r'\d+', CONFIG['modem']): raise ValueError('Invalid modem index')
    # Fail closed on non-Linux hosts or missing LAN bridge. Binding an IP alone
    # does not restrict which interface a routed packet arrived through.
    socket.if_nametoindex('br-lan')
    server = HTTPServer((CONFIG['bind'], 8765), Handler, bind_and_activate=False)
    server.socket.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, b'br-lan\0')
    server.server_bind()
    server.server_activate()
    server.serve_forever()

if __name__ == '__main__': main()
