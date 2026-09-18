#!/usr/bin/env python3
"""Test the real initramfs storage decision with mocked block tools."""
from pathlib import Path
import os
import subprocess
import tempfile
source = (Path(__file__).resolve().parents[1] / 'init-podroid').read_text()
start = source.index('if ! mount -t ext4')
end = source.index('\n# Android grows', start)
for fresh, known, repair, formatted, success in [
    (True, False, 4, True, True),
    (False, True, 1, False, True),
    (False, False, 4, False, False),
    (True, True, 4, False, False),
]:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / 'cmdline').write_text('podroid.storage.new=1' if fresh else '')
        tools = {
            'mount': '[ -f "$TEST_ROOT/mounted" ] && exit 0; touch "$TEST_ROOT/mounted"; exit 1',
            'blkid': f'exit {0 if known else 2}',
            'e2fsck': f'exit {repair}',
            'mkfs.ext4': 'touch "$TEST_ROOT/formatted"',
            'sh': 'exit 99',
        }
        for name, body in tools.items():
            path = root / name
            path.write_text('#!/bin/sh\n' + body + '\n')
            path.chmod(0o755)
        script = source[start:end].replace('/proc/cmdline', str(root / 'cmdline')).replace('/dev/console', str(root / 'console'))
        env = dict(os.environ, PATH=str(root) + ':' + os.environ['PATH'], TEST_ROOT=str(root))
        result = subprocess.run(['/bin/sh', '-ec', script], env=env)
        assert (result.returncode == 0) == success, (fresh, known, repair, result.returncode)
        assert (root / 'formatted').exists() == formatted
print('Storage init: new disks format; existing disks and failed repairs never reformat')
