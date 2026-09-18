#!/usr/bin/env python3
"""Consume Mimir's published document; never parse retired CLI tree output."""
import json
import os
from pathlib import Path
import re
root = Path(__file__).resolve().parent.parent
source = Path(os.environ.get("VEGVISIR_FILE", str(Path.home() / ".mim/vegvisir.json")))
doc = json.loads(source.read_text())
region = os.environ.get("VEGVISIR_REGION", "local")
servers = doc["regions"][region]
nomad = servers["nomad_servers"]
nats = servers["nats_servers"]
if not nomad or not nats:
    raise SystemExit("Refusing empty Nomad/NATS discovery targets")
values = {
    "CONF_nomad.client.server_join.retry_join": [n["nomad_rpc"] for n in nomad],
    "CONF_bark.identity.nats_urls": [n["nats_url"] for n in nats],
    "CONF_bark.dns.nats_urls": [n["nats_url"] for n in nats],
    "CONF_yggdrasil.peers": list(dict.fromkeys(p for n in nomad for p in n["yggdrasil_peers"])),
}
p = root / "build-rootfs/files/etc/ratatoskr/env"
text = p.read_text()
for key, value in values.items():
    if not value or any(not v for v in value):
        raise SystemExit(f"Invalid discovery targets: {key}")
    text, count = re.subn(r"^" + re.escape(key) + r"=.*$", key + "=" + json.dumps(value, separators=(",", ":")), text, flags=re.M)
    if count != 1:
        raise SystemExit(f"Expected one {key} setting")
p.write_text(text)
(p.parent / "vegvisir.json").write_text(json.dumps(doc, indent=2) + "\n")
print(f"Synced {region}: {len(nomad)} Nomad, {len(nats)} NATS")
