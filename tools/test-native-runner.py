#!/usr/bin/env python3
"""Verify identity, bootstrap DNS, and literal environment handling on respawn."""
from pathlib import Path
import os
import subprocess
import tempfile

source = Path(__file__).resolve().parents[1] / 'build-rootfs/files/usr/local/bin/podroid-ratatoskr-run'
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    for path in ['etc/ratatoskr', 'run', 'proc', 'var/lib/ratatoskr/nomad/client', 'var/lib/ratatoskr/nats-client']:
        (root / path).mkdir(parents=True, exist_ok=True)
    (root / 'etc/ratatoskr/env').write_text('CONF_test.literal=$(touch SHOULD_NOT_EXIST)\n')
    (root / 'proc/cmdline').write_text('console=ttyAMA0\n')
    (root / 'etc/resolv.conf').write_text('nameserver ::1\n')
    (root / 'run/podroid-resolv.conf').write_text('nameserver 10.0.2.3\n')
    (root / 'var/lib/ratatoskr/nomad/client/client-id').write_text('12345678-abcd\n')
    (root / 'var/lib/ratatoskr/nats-client/internal.creds').write_text('test-placeholder\n')
    helper = root / 'node-name'
    helper.write_text('#!/bin/sh\nprintf "cnhkg-babek\\n"\n')
    helper.chmod(0o755)
    script = source.read_text().replace('/usr/local/bin/podroid-node-name', str(helper))
    for prefix in ['/etc/', '/run/', '/var/lib/', '/proc/']:
        script = script.replace(prefix, str(root) + prefix)
    script = script.replace('/usr/local/bin/ratatoskr', '/usr/bin/env')
    runner = root / 'runner.sh'
    runner.write_text(script)
    def run():
        return subprocess.check_output(['sh', str(runner)], text=True, cwd=root)
    output = run()
    assert 'CONF_bark.node_name=cnhkg-babek\n' in output
    assert 'CONF_bark.nats.operator.internal_credentials_file=' in output
    assert 'CONF_test.literal=$(touch SHOULD_NOT_EXIST)\n' in output
    assert not (root / 'SHOULD_NOT_EXIST').exists()
    assert 'CONF_bark.control_plane_firewall.enabled=false\n' in output
    (root / 'proc/cmdline').write_text('podroid.backend=avf\n')
    assert 'CONF_bark.control_plane_firewall.enabled=' not in run()
    (root / 'proc/cmdline').write_text('console=ttyAMA0\n')
    assert (root / 'etc/resolv.conf').read_text() == 'nameserver 10.0.2.3\n'
    (root / 'var/lib/ratatoskr/nomad/client/client-id').write_text('99999999-abcd\n')
    assert 'CONF_bark.node_name=cnhkg-babek\n' in run()
    (root / 'etc/ratatoskr/env').write_text('CONF_bark.node_name=custom-phone\nCONF_bark.control_plane_firewall.enabled=true\n')
    assert 'CONF_bark.node_name=custom-phone\n' in run()
    assert 'CONF_bark.control_plane_firewall.enabled=false\n' not in run()
    assert 'CONF_bark.control_plane_firewall.enabled=true\n' in run()
print('Native runner: stable identity, explicit name, literal env and DNS recovery passed')
