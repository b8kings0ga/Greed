#!/usr/bin/env python3
"""Exercise the actual network selection loop against device/virtual fixtures."""
from pathlib import Path
import subprocess
import tempfile

source = Path('build-rootfs/files/etc/init.d/podroid-network').read_text()
loop = source[source.index('        for candidate in '):source.index('        [ -n "$NETIF" ]')]
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    for name in ('dummy0', 'ifb0', 'ifb1', 'eth0', 'lxcbr0', 'mim0'):
        (root / name).mkdir()
        (root / name / 'type').write_text('1\n')
    (root / 'eth0' / 'device').mkdir()
    script = 'NETIF=""\n' + loop.replace('/sys/class/net', str(root)) + '\nprintf "%s" "$NETIF"'
    assert subprocess.check_output(['sh', '-c', script], text=True) == 'eth0'
    (root / 'eth0' / 'device').rmdir()
    assert subprocess.check_output(['sh', '-c', script], text=True) == ''
print('Network selection: virtio Ethernet selected; IFB/bridges rejected')
