"""Conservative, board/USB-profile-specific driver provisioning; no AT commands."""
import json
import os
import re
import subprocess
from pathlib import Path

PROFILE = 'mg1300-dji-gen1'
VID, PID = '2ca3', '4006'
SYSFS = Path('/sys/bus/usb/devices')
CONFIG_PATH = Path('/etc/router-sms.json')
NEW_ID = Path('/sys/bus/usb-serial/drivers/option1/new_id')

def command(args, timeout=8):
    result = subprocess.run(args, capture_output=True, encoding='utf-8', errors='replace', timeout=timeout)
    if result.returncode: raise RuntimeError(result.stderr.strip()[:300] or 'Command failed')
    return result.stdout

def board():
    return json.loads(command(['/bin/ubus', 'call', 'system', 'board'])).get('board_name', '')

def devices():
    found = []
    for vendor in SYSFS.glob('*/idVendor'):
        root = vendor.parent
        try:
            if vendor.read_text().strip().lower() != VID or (root/'idProduct').read_text().strip().lower() != PID: continue
            interfaces = []
            for node in sorted(SYSFS.glob(root.name + ':*')):
                driver = (node/'driver').resolve().name if (node/'driver').exists() else ''
                interfaces.append({'number': (node/'bInterfaceNumber').read_text().strip(),
                                   'class': (node/'bInterfaceClass').read_text().strip().lower(),
                                   'driver': driver})
            found.append({'path': str(root.resolve()), 'usb': root.name,
                          'product': (root/'product').read_text().strip(), 'vendor': VID, 'pid': PID,
                          'interfaces': interfaces})
        except OSError: continue
    return found

def inspect(config):
    name, hardware = board(), devices()
    return {'profile': PROFILE, 'board': name, 'supported_board': name == 'glinet,gl-mg1300',
            'devices': hardware, 'profile_enabled': config.get('hardware_profile') == PROFILE,
            'voice_enabled': config.get('enable_voice') is True,
            'compatible': name == 'glinet,gl-mg1300' and len(hardware) == 1 and 'QDC507' in hardware[0]['product']}

def target_device():
    if board() != 'glinet,gl-mg1300': raise RuntimeError('仅支持 GL-MG1300，未更改任何设备绑定')
    hardware = devices()
    if len(hardware) != 1 or 'QDC507' not in hardware[0]['product']:
        raise RuntimeError('请仅插入一个 DJI 一代 QDC507 模块（USB 2ca3:4006）')
    target = hardware[0]
    serial = [i for i in target['interfaces'] if i['class'] == 'ff']
    if {i['number'] for i in serial} != {'00', '01', '02', '03'}:
        raise RuntimeError('模块接口布局不匹配，拒绝自动绑定')
    if any(i['driver'] not in ('', 'option') for i in serial):
        raise RuntimeError('串口已由其他驱动占用，拒绝解绑现有驱动')
    return target

def register_driver(target):
    # Never unbind CDC Ethernet, change USB mode, or open ttyUSB ports.
    if not NEW_ID.exists(): command(['/sbin/modprobe', 'option'])
    if not NEW_ID.exists(): raise RuntimeError('缺少 option 驱动，请安装匹配固件的 kmod-usb-serial-option')
    entries = NEW_ID.read_text().lower().splitlines()
    if not any(line.split()[:2] == [VID, PID] for line in entries):
        NEW_ID.write_text(VID + ' ' + PID + ' ff\n')
    return {'driver_registered': True, 'usb': target['usb']}

def resolve_modem():
    target = target_device()
    inventory = json.loads(command(['/usr/bin/mmcli', '--timeout=5', '-L', '--output-json']))
    paths = re.findall(r'/org/freedesktop/ModemManager1/Modem/\d+', json.dumps(inventory))
    matches = []
    for path in dict.fromkeys(paths):
        generic = json.loads(command(['/usr/bin/mmcli', '--timeout=5', '-m', path, '--output-json'])).get('modem', {}).get('generic', {})
        if generic.get('model') == 'QDC507' and generic.get('device') == target['path']:
            matches.append(path.rsplit('/', 1)[1])
    if len(matches) != 1: raise RuntimeError('模块正在初始化或未由 ModemManager 识别，请稍后刷新设备检测')
    return matches[0]

def enable_disabled_modem(index):
    value = json.loads(command(['/usr/bin/mmcli', '--timeout=5', '-m', index, '--output-json']))
    state = value.get('modem', {}).get('generic', {}).get('state')
    if state == 'disabled':
        command(['/usr/bin/mmcli', '--timeout=5', '-m', index, '--enable'])
    return state

def save_config(config):
    previous = CONFIG_PATH.read_bytes()
    backup = CONFIG_PATH.with_name('router-sms.json.before-device-setup')
    if not backup.exists():
        fd = os.open(str(backup), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as f: f.write(previous)
    temp = CONFIG_PATH.with_name('router-sms.json.setup-tmp')
    fd = os.open(str(temp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as f:
        json.dump(config, f); f.flush(); os.fsync(f.fileno())
    os.chmod(str(temp), 0o600)
    os.replace(str(temp), str(CONFIG_PATH))

def apply(config):
    target = target_device()
    # No reset/restart of ModemManager: preserve working SMS and data sessions.
    register_driver(target)
    command(['/etc/init.d/modemmanager', 'enable'])
    running = subprocess.run(['/etc/init.d/modemmanager', 'running'], capture_output=True, timeout=5)
    if running.returncode: command(['/etc/init.d/modemmanager', 'start'])
    command(['/etc/init.d/router-sms-bind', 'enable'])
    updated = dict(config, hardware_profile=PROFILE)
    pending = False
    try:
        updated['modem'] = resolve_modem()
        enable_disabled_modem(updated['modem'])
    except RuntimeError: pending = True
    save_config(updated)
    config.update(updated)
    return {'ok': True, 'pending': pending, 'modem': config['modem'],
            'note': '已持久化驱动绑定与 ModemManager 自启；未改动 USB 网卡、APN、IMEI 或网络配置'}

def boot():
    with CONFIG_PATH.open() as f: config = json.load(f)
    if config.get('hardware_profile') != PROFILE: return
    try: register_driver(target_device())
    except RuntimeError: pass  # USB absent at boot; hotplug retries when inserted.

if __name__ == '__main__': boot()
