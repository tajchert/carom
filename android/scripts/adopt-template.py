#!/usr/bin/env python3
"""Rename a fresh starter checkout. Defaults to a preview; --apply writes changes."""
import argparse
import json
import re
import sys
from pathlib import Path
from xml.sax.saxutils import escape

EXCLUDED = {'.git', '.gradle', '.kotlin', '.idea', '.worktrees', '.superpowers', 'build', 'scripts', '__pycache__'}
JAVA_KEYWORDS = set('abstract assert boolean byte case catch char const default double enum extends final finally float goto implements instanceof int long native new private protected public short static strictfp switch synchronized transient void volatile throws'.split())
KEYWORDS = JAVA_KEYWORDS | {'class', 'object', 'fun', 'val', 'var', 'in', 'is', 'when', 'package', 'return', 'interface', 'import', 'null', 'true', 'false', 'this', 'super', 'try', 'throw', 'while', 'for', 'else', 'if', 'as', 'break', 'continue', 'do', 'typealias', 'typeof'}


def validate(args):
    if not re.fullmatch(r'[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+', args.package) or any(p in KEYWORDS for p in args.package.split('.')):
        raise ValueError('Package must have at least two lowercase Kotlin-safe segments.')
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9]*', args.symbol_prefix):
        raise ValueError('Symbol prefix must be a Kotlin identifier starting with a letter.')
    if not re.fullmatch(r'[a-z][a-z0-9-]*', args.project_name):
        raise ValueError('Project name must be a lowercase slug.')
    if len(args.host) > 253 or not all(re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', part) for part in args.host.split('.')) or '.' not in args.host:
        raise ValueError('Host must be a DNS hostname, without a scheme or path.')
    if not re.fullmatch(r'[a-z][a-z0-9+.-]*', args.scheme) or args.scheme in {'http', 'https'}:
        raise ValueError('Scheme must be a custom URI scheme.')
    if not args.app_name.strip() or any(ord(c) < 32 for c in args.app_name) or any(c in args.app_name for c in ['%', '@', '?', '\\']):
        raise ValueError('App name must be nonempty display text without Android resource syntax.')


def adopt(root, args):
    validate(args)
    identity = root / '.template-identity.json'
    old = json.loads(identity.read_text())
    new = dict(package=args.package, app_name=args.app_name, host=args.host, scheme=args.scheme,
               symbol_prefix=args.symbol_prefix, project_name=args.project_name, database=args.project_name + '-database')
    if old == new:
        print('Identity already matches; no changes.')
        return
    replacements = [(old['package'], new['package']), (old['host'], new['host']),
                    (old['scheme'], new['scheme']), (old['symbol_prefix'], new['symbol_prefix']),
                    (old['project_name'], new['project_name']), (old['database'], new['database'])]
    # Substitute against the original text once: inserted values must never be
    # interpreted as another old identity token.
    replacements += [(old['host'].upper(), new['host'].upper()), (old['scheme'].upper(), new['scheme'].upper()),
                     (old['app_name'], new['app_name'])]
    mapping = {}
    for token, replacement in replacements:
        if token in mapping and mapping[token] != replacement:
            raise ValueError('Current identity has overlapping values; adopt from a fresh checkout instead.')
        mapping[token] = replacement
    pattern = re.compile('|'.join(re.escape(key) for key in sorted(mapping, key=len, reverse=True)))
    path_mapping = {old['package'].replace('.', '/'): new['package'].replace('.', '/'),
                    old['package']: new['package'], old['symbol_prefix']: new['symbol_prefix']}
    path_pattern = re.compile('|'.join(re.escape(key) for key in sorted(path_mapping, key=len, reverse=True)))
    operations = []
    for path in sorted(root.rglob('*')):
        rel = path.relative_to(root)
        if not path.is_file() or path.is_symlink() or any(part in EXCLUDED for part in rel.parts) or path.name in {'.git', 'local.properties', '.template-identity.json'}:
            continue
        # Generated artifacts encode class/package names and must be regenerated.
        if ('baselineProfiles' in rel.parts or ('reference' in rel.parts and any(p.startswith('screenshotTest') for p in rel.parts))):
            if old['package'] != new['package'] or old['symbol_prefix'] != new['symbol_prefix']:
                operations.append((path, None, None))
            continue
        try:
            original = path.read_text(encoding='utf-8')
        except (UnicodeDecodeError, ValueError):
            continue
        text = original
        # Historical reports describe the original checkout. Leave them as history.
        if rel.parts[:2] == ('docs', 'superpowers') or path.name.endswith('_REFRESH.md'):
            continue
        text = pattern.sub(lambda match: mapping[match[0]], original)
        if path.name == 'strings.xml' and 'name="app_name"' in text:
            label = escape(args.app_name).replace("'", "\\'").replace('"', '\\"')
            text = re.sub(r'(<string name="app_name">).*?(</string>)', lambda m: m[1] + label + m[2], text)
        destination = root / path_pattern.sub(lambda match: path_mapping[match[0]], str(rel))
        if text != original or path != destination:
            for ancestor in destination.parents:
                if ancestor == root.parent:
                    break
                if ancestor.is_symlink() or (ancestor.exists() and not ancestor.is_dir()):
                    raise ValueError(f'Rename destination has an unsafe parent: {ancestor}')
            if destination.is_symlink():
                raise ValueError(f'Rename would write through a symlink: {destination}')
            if destination != path and destination.exists():
                raise ValueError(f'Rename would overwrite {destination.relative_to(root)}')
            operations.append((path, destination, text))
    destinations = [dest for _, dest, _ in operations if dest is not None]
    if len(destinations) != len(set(destinations)):
        raise ValueError('Multiple source files map to the same destination.')
    for source, destination, text in operations:
        print(('remove ' if destination is None else 'update ') + str(source.relative_to(root)) +
              (f' -> {destination.relative_to(root)}' if destination and source != destination else ''))
    print(f'{len(operations)} file changes; ' + ('applying.' if args.apply else 'preview only (use --apply).'))
    if not args.apply:
        return
    originals = {source: (source.read_bytes(), source.stat().st_mode) for source, _, _ in operations}
    identity_bytes = identity.read_bytes()
    try:
        for source, destination, text in operations:
            if destination is None:
                source.unlink()
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_text(text)
                if source != destination:
                    destination.chmod(source.stat().st_mode)
                    source.unlink()
        identity.write_text(json.dumps(new, indent=2) + '\n')
    except OSError:
        # A failed write must not leave the checkout half-adopted.
        for source, destination, _ in operations:
            if destination is not None and destination != source and destination.exists():
                destination.unlink()
        for source, (content, mode) in originals.items():
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_bytes(content)
            source.chmod(mode)
        identity.write_bytes(identity_bytes)
        raise
    print('Regenerate Baseline Profiles and screenshot references; replace launcher icons and publish assetlinks.json before shipping.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package', required=True)
    parser.add_argument('--app-name', required=True)
    parser.add_argument('--project-name', required=True)
    parser.add_argument('--symbol-prefix', required=True)
    parser.add_argument('--host', required=True)
    parser.add_argument('--scheme', required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    try:
        adopt(Path(__file__).resolve().parent.parent, args)
    except (ValueError, OSError, KeyError) as error:
        parser.exit(2, f'{error}\n')


if __name__ == '__main__':
    main()
