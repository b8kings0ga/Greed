#!/usr/bin/env python3
"""Check existing overlay upgrade, custom listener preservation and idempotence."""
from pathlib import Path
import os
import subprocess
import tempfile
script = Path(__file__).resolve().parents[1] / 'build-rootfs/files/etc/podroid/migrations/39.sh'
for setting in ['', 'CONF_bark.qlite.listen=127.0.0.1:14227\n',
                'CONF_bark__qlite__listen=127.0.0.1:14227\n',
                'CONF_bark.qlite.listen=172.26.64.1:14227\n']:
    with tempfile.TemporaryDirectory() as directory:
        config = Path(directory) / 'etc/ratatoskr/env'
        config.parent.mkdir(parents=True)
        config.write_text('CONF_keep=value\n' + setting)
        env = {**os.environ, 'PODROID_MIGRATION_ROOT': directory}
        subprocess.run(['sh', str(script)], env=env, check=True)
        first = config.read_text()
        subprocess.run(['sh', str(script)], env=env, check=True)
        assert first == config.read_text() and 'CONF_keep=value' in first
        expected = '172.26.64.1:14227' if '172.26.64' in setting else '0.0.0.0:14227'
        assert expected in first
print('Membership QLite migration: upgrade, preservation, idempotence passed')
