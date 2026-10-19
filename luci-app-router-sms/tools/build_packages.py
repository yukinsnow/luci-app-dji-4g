from pathlib import Path
import tarfile
import io
import time
import hashlib
import gzip

SOURCE=Path(__file__).resolve()
ROOT=SOURCE.parents[2]
OUT=ROOT/'dist'
OUT.mkdir(exist_ok=True)
APP=ROOT/'luci-app-router-sms'
BACKEND=ROOT/'router-sms'/'backend'
STAMP=1792368000
def tar_bytes(files):
    output=io.BytesIO()
    with tarfile.open(fileobj=output,mode='w',format=tarfile.GNU_FORMAT) as archive:
        parents=set()
        for name in files:
            parts=Path(name.lstrip('/')).parts[:-1]
            for i in range(1,len(parts)+1):parents.add('/'.join(parts[:i]))
        for name in sorted(parents,key=lambda n:(n.count('/'),n)):
            item=tarfile.TarInfo('./'+name+'/');item.type=tarfile.DIRTYPE;item.mode=0o755;item.mtime=STAMP;item.uid=0;item.gid=0
            archive.addfile(item)
        for name,(data,mode) in sorted(files.items()):
            item=tarfile.TarInfo('./'+name.lstrip('/'));item.size=len(data);item.mode=mode;item.mtime=STAMP;item.uid=0;item.gid=0
            archive.addfile(item,io.BytesIO(data))
    return gzip.compress(output.getvalue(), mtime=0)
def package(name,version,depends,files,scripts):
    control=('Package: '+name+'\nVersion: '+version+'\nArchitecture: all\nMaintainer: Local Router Tools\nSection: luci\nPriority: optional\nDepends: '+depends+'\nDescription: GL-MG1300 QDC507 SMS and call control\n').encode()
    meta={'control':(control,0o644)}
    meta.update({k:(v.encode(),0o755) for k,v in scripts.items()})
    parts=[('debian-binary',b'2.0\n'),('control.tar.gz',tar_bytes(meta)),('data.tar.gz',tar_bytes(files))]
    target=OUT/(name+'_'+version+'_all.ipk')
    # GL.iNet OpenWrt opkg expects the OpenWrt tar.gz container, not Debian ar.
    target.write_bytes(tar_bytes({filename:(data,0o644) for filename,data in parts}))
    print(target.name,target.stat().st_size,hashlib.sha256(target.read_bytes()).hexdigest())
    return target

files={}
for name in ['router_api.py','router_setup.py']:
    files['usr/lib/router-sms/'+name]=((BACKEND/name).read_bytes(),0o644)
files['etc/init.d/router-sms']=((BACKEND/'router-sms.init').read_bytes(),0o755)
files['etc/init.d/router-sms-bind']=((BACKEND/'router-sms-bind.init').read_bytes(),0o755)
files['etc/hotplug.d/usb/80-router-sms-bind']=((BACKEND/'80-router-sms-bind').read_bytes(),0o755)
files['lib/upgrade/keep.d/router-sms']=(b'/etc/router-sms.json\n',0o644)
postinst='''#!/bin/sh
[ -n "$IPKG_INSTROOT" ] && exit 0
umask 077
if [ ! -f /etc/router-sms.json ]; then
 /usr/bin/python3 -c 'import json,secrets;json.dump(dict(bind="192.168.8.1",lan_cidr="192.168.8.0/24",modem="0",enable_voice=False,token=secrets.token_hex(32)),open("/etc/router-sms.json","w"))' || exit 1
fi
chmod 600 /etc/router-sms.json
if /usr/bin/python3 -c 'import json,sys;sys.exit(0 if json.load(open("/etc/router-sms.json")).get("hardware_profile")=="mg1300-dji-gen1" else 1)'; then
 /etc/init.d/router-sms-bind enable
fi
/etc/init.d/router-sms enable
/etc/init.d/router-sms restart
exit 0
'''
prerm='''#!/bin/sh
[ -n "$IPKG_INSTROOT" ] && exit 0
/etc/init.d/router-sms stop
/etc/init.d/router-sms disable
/etc/init.d/router-sms-bind disable
exit 0
'''
package('router-sms-api','0.3.2-1','python3-light, python3-urllib, python3-codecs, python3-logging, python3-email, python3-openssl, modemmanager, kmod-usb-serial-option',files,{'postinst':postinst,'prerm':prerm})
files={}
for p in (APP/'root').rglob('*'):
    if p.is_file():
        rel=p.relative_to(APP/'root').as_posix()
        files[rel]=(p.read_bytes(),0o755 if rel.startswith('usr/libexec/rpcd/') else 0o644)
postinst='''#!/bin/sh
[ -n "$IPKG_INSTROOT" ] && exit 0
rm -f /tmp/luci-indexcache*
/etc/init.d/rpcd reload
exit 0
'''
postrm='''#!/bin/sh
[ -n "$IPKG_INSTROOT" ] && exit 0
rm -f /tmp/luci-indexcache*
/etc/init.d/rpcd reload
exit 0
'''
package('luci-app-router-sms','0.3.1-1','luci-base, rpcd, router-sms-api (>= 0.3.2-1)',files,{'postinst':postinst,'postrm':postrm})

(OUT/'SHA256SUMS.txt').write_text(''.join(
    hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n'
    for p in sorted(OUT.glob('*.ipk'))), encoding='utf-8')
