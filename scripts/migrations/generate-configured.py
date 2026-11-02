#!/usr/bin/env python3
"""Reproduce the nonpublic upgrade location without rewriting deployed SQL."""
import argparse
from pathlib import Path
import re

root = Path(__file__).resolve().parents[2]
source = root / 'src/main/resources/db/migration'
target = source.parent / 'configured'
parser = argparse.ArgumentParser()
parser.add_argument('--check', action='store_true')
args = parser.parse_args()
if not args.check:
    target.mkdir(exist_ok=True)
sources = sorted(source.rglob('*.sql'))
expected_names = {path.relative_to(source) for path in sources}
if {path.relative_to(target) for path in target.rglob('*.sql')} - expected_names:
    raise SystemExit('Configured location contains stale migrations')
for path in sources:
    content = path.read_bytes()
    version = re.match(r'V(\d+)__', path.name)
    if version and 18 <= int(version[1]) <= 25:
        content = re.sub(rb'\bpublic\.', b'"${flyway:defaultSchema}".', content)
    relative = path.relative_to(source)
    destination = target / relative
    if args.check:
        if not destination.exists() or destination.read_bytes() != content:
            raise SystemExit(f'Configured migration differs from its source: {relative}')
    else:
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)
