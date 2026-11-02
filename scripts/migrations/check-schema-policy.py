#!/usr/bin/env python3
"""Keep deployed versioned SQL immutable and new SQL independent of public."""
from pathlib import Path
import hashlib
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
legacy = {}
for line in (root / 'scripts/backend-verification/accepted/migrations.sha256').read_text().splitlines():
    digest, name = line.split(None, 1)
    if Path(name).name.startswith('V'):
        legacy[name] = digest
for name, digest in legacy.items():
    if hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
        raise SystemExit(f'Applied versioned migration changed: {name}')
for directory in ['src/main/resources/db/migration', 'src/main/resources/db/bootstrap', 'src/main/resources/db/configured']:
    for path in (root / directory).rglob('*.sql'):
        if str(path.relative_to(root)) in legacy:
            continue
        if re.search(r'\bpublic\"?\s*\.', path.read_text(), re.IGNORECASE):
            raise SystemExit(f'Hardcoded application schema: {path.relative_to(root)}')
subprocess.run([sys.executable, str(root / 'scripts/migrations/generate-baseline.py'), '--check'], check=True)
subprocess.run([sys.executable, str(root / 'scripts/migrations/generate-configured.py'), '--check'], check=True)
print('PASS: immutable legacy versions and configured-schema migration policy')
