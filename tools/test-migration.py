#!/usr/bin/env python3
"""Exercise the real migration runner: failure must not consume a version."""
from pathlib import Path
import subprocess
import tempfile

source = Path(__file__).resolve().parent.parent / "build-rootfs/files/etc/init.d/podroid-migrate"
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    (root / "scripts").mkdir()
    (root / "version").write_text("33\n")
    (root / "applied").write_text("27\n")
    script = source.read_text()
    script = script.replace('SYSVER_FILE="/etc/podroid/system-version"', f'SYSVER_FILE="{root}/version"')
    script = script.replace('APPLIED_FILE="/mnt/persist/.podroid/applied-version"', f'APPLIED_FILE="{root}/applied"')
    script = script.replace('MIGRATIONS_DIR="/etc/podroid/migrations"', f'MIGRATIONS_DIR="{root}/scripts"')
    script = script.replace('mkdir -p /mnt/persist/.podroid', ':').replace('/dev/console', '/dev/null')
    runner = root / "runner"
    runner.write_text(script + '\nebegin() { :; }; eend() { :; }; start\n')
    migration = root / "scripts/33.sh"
    migration.write_text('exit 1\n')
    assert subprocess.run(['sh', str(runner)]).returncode != 0
    assert (root / 'applied').read_text().strip() == '27'
    migration.write_text(f'echo applied >> "{root}/calls"\n')
    assert subprocess.run(['sh', str(runner)]).returncode == 0
    assert (root / 'applied').read_text().strip() == '33'
    assert subprocess.run(['sh', str(runner)]).returncode == 0
    assert (root / 'calls').read_text().splitlines() == ['applied']
print('Migration failure, retry, and no-repeat checks passed')
