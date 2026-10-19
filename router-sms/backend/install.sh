#!/bin/sh
set -eu
cd "$(dirname "$0")"
command -v python3 >/dev/null || { echo 'Install python3 first (see README)'; exit 1; }
command -v mmcli >/dev/null
mkdir -p /usr/lib/router-sms
cp router_api.py /usr/lib/router-sms/router_api.py
cp router_setup.py /usr/lib/router-sms/router_setup.py
cp router-sms.init /etc/init.d/router-sms
cp router-sms-bind.init /etc/init.d/router-sms-bind
mkdir -p /etc/hotplug.d/usb
cp 80-router-sms-bind /etc/hotplug.d/usb/80-router-sms-bind
chmod 755 /etc/init.d/router-sms
chmod 755 /etc/init.d/router-sms-bind /etc/hotplug.d/usb/80-router-sms-bind
if [ ! -e /etc/router-sms.json ]; then
    umask 077
    python3 -c 'import json,secrets; json.dump(dict(bind="192.168.8.1",lan_cidr="192.168.8.0/24",modem="0",enable_voice=False,token=secrets.token_hex(32)),open("/etc/router-sms.json","w"))'
fi
chmod 600 /etc/router-sms.json
echo 'Installed. Configure firewall and inspect /etc/router-sms.json before starting; see README.'
