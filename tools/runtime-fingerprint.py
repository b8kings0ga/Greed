#!/usr/bin/env python3
"""Hash actual runtime inputs, including local source edits and imported releases."""
import hashlib
import os
from pathlib import Path
import subprocess
root = Path(__file__).resolve().parent.parent
mimir = Path(os.environ.get('MIMIR_DIR', root / '../Mimir')).resolve()
paths = subprocess.check_output([
    'git', '-C', str(mimir), 'ls-files', '-z', '--cached', '--others', '--exclude-standard', '--',
    'go.mod', 'go.sum', 'Dockerfile', 'cmd/ratatoskr', 'internal', 'pkg', 'third_party',
    'mim-net/dist/10-mim-net.conflist', 'mim-net/Cargo.toml', 'scripts/mim-net-version.sh',
]).decode().split('\0')
hash = hashlib.sha256()
for name in sorted(set(paths) - {''}):
    path = mimir / name
    hash.update(name.encode() + b'\0')
    hash.update(path.read_bytes() if path.is_file() else b'<deleted>')
for name in ['dist/bin/arm64/mim-net', 'dist/runtime-tools/nomad-min-linux-arm64.tar.zst']:
    hash.update(name.encode() + b'\0')
    with (mimir / name).open('rb') as file:
        for block in iter(lambda: file.read(1024 * 1024), b''):
            hash.update(block)
hash.update((root / 'tools/build-ratatoskr-native.sh').read_bytes())
print(hash.hexdigest())
