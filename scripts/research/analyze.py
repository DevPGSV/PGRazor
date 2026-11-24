"""Offline package inventory and bounded Cpp2IL/JADX structural catalog."""
import argparse
from collections import Counter
import gzip
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
from zipfile import ZipFile

import metadata_v39
import fixture

CPP = '/opt/research/Cpp2IL'
JADX = '/opt/research/jadx/bin/jadx'
OUT = Path('/output')
GAME = Path('/input/game.apkm')
BASE = 'base.apk'
SPLIT = 'split_config.arm64_v8a.apk'
METADATA = 'assets/bin/Data/Managed/Metadata/global-metadata.dat'
MANAGERS = 'assets/bin/Data/globalgamemanagers'
ELF = 'lib/arm64-v8a/libil2cpp.so'
LIMIT = 350 * 1024 * 1024
TARGET = re.compile(r'pokemon|pokedex|pok[eé]dex|incubat|egg|itembag|inventory|storage|journal|player(stats|service|data|profile)|iapservice|stardust|currency', re.I)
TYPE = re.compile(r'\b(class|struct|interface|enum|record)\s+([^ :{]+)')
MODIFIERS = {'public', 'private', 'protected', 'internal', 'static', 'readonly', 'const',
             'virtual', 'sealed', 'abstract', 'override', 'new', 'unsafe', 'extern', 'partial'}


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def extract(archive, name, destination):
    entry = archive.getinfo(name)
    if entry.file_size > LIMIT:
        raise ValueError(f'Member exceeds extraction cap: {name}')
    with archive.open(entry) as source, destination.open('xb') as target:
        shutil.copyfileobj(source, target, 1024 * 1024)


def inspect(apkm, work):
    with ZipFile(apkm) as bundle:
        outer = [{'name': x.filename, 'bytes': x.file_size} for x in bundle.infolist()]
        for name in (BASE, SPLIT):
            extract(bundle, name, work / name)
    with ZipFile(work / BASE) as base:
        extract(base, METADATA, work / 'global-metadata.dat')
        with base.open(MANAGERS) as stream:
            header = stream.read(8192)
        versions = sorted({x.decode('ascii') for x in re.findall(rb'\b\d{4}\.\d+\.\d+[abfp]\d+\b', header)})
        dex = [x.filename for x in base.infolist() if re.fullmatch(r'classes\d*\.dex', x.filename)]
    with ZipFile(work / SPLIT) as split:
        extract(split, ELF, work / 'libil2cpp.so')
        libraries = [x for x in split.namelist() if x.startswith('lib/') and x.endswith('.so')]
    with (work / 'global-metadata.dat').open('rb') as stream:
        magic, metadata_version = struct.unpack('<II', stream.read(8))
    if magic != 0xFAB11BAF:
        raise ValueError('Metadata does not have the standard IL2CPP magic; protected/unsupported input is out of scope')
    with (work / 'libil2cpp.so').open('rb') as stream:
        header = stream.read(64)
    if header[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', header, 18)[0] != 183:
        raise ValueError('Unexpected ELF format or ABI')
    if len(versions) != 1:
        raise ValueError('Could not establish one Unity version from globalgamemanagers')
    return {'apkm_sha256': digest(apkm), 'outer_members': outer, 'dex': dex, 'libraries': libraries,
            'unity': versions[0], 'metadata_version': metadata_version, 'architecture': 'ELF64 AArch64',
            'metadata_path': METADATA, 'elf_path': ELF,
            'metadata_sha256': digest(work / 'global-metadata.dat'), 'elf_sha256': digest(work / 'libil2cpp.so')}


def signature(text):
    tokens = text.strip().split()
    while tokens and tokens[0] in MODIFIERS:
        tokens.pop(0)
    return ' '.join(tokens[:-1]), tokens[-1] if tokens else ''


def parse_type_file(path, assembly):
    """Normalize the layout emitted by the pinned DiffableCsOutputFormat, retaining declarations."""
    namespace = ''
    active = {}
    pending = None
    for line in path.read_text(encoding='utf-8-sig', errors='strict').splitlines():
        text = line.strip()
        depth = len(line) - len(line.lstrip('\t'))
        if not text or text.startswith(('//', '[')):
            continue
        if text.startswith('namespace '):
            namespace = text[10:].rstrip(';')
            continue
        if text == '{':
            continue
        if text == '}':
            if depth in active:
                yield active.pop(depth)
            pending = None
            continue
        owner = active.get(depth - 1)
        match = TYPE.search(text)
        if match and '(' not in text and '//Field offset:' not in text and not text.endswith((';', ',')):
            kind, name = match.groups()
            active[depth] = {
                'assembly': assembly, 'namespace': namespace,
                'type': name if owner is None else owner['type'] + '+' + name,
                'kind': kind, 'declaration': text, 'fields': [], 'properties': [], 'methods': [], 'enum_values': [],
            }
        elif owner is not None:
            if '//Field offset:' in text:
                declaration, offset = text.split('//Field offset:', 1)
                # Drop initializer values, including embedded constants and static data blobs.
                field_type, name = signature(declaration.rstrip().removesuffix(';').split(' = ', 1)[0])
                owner['fields'].append({'name': name, 'type': field_type, 'offset_as_printed': offset.split()[0]})
            elif owner['kind'] == 'enum' and text.endswith(','):
                name, _, value = text[:-1].partition(' = ')
                owner['enum_values'].append({'name': name, 'value': value})
            elif text.endswith(') { }'):
                owner['methods'].append(text.removesuffix(' { }'))
            elif not text.endswith(';') and '(' not in text and ' event ' not in text:
                prop_type, name = signature(text)
                pending = (owner, depth, {'name': name, 'type': prop_type, 'accessors': []})
        elif pending is not None and depth == pending[1] + 1:
            word = text.split(maxsplit=1)[0].rstrip(';')
            if word in {'get', 'set'}:
                prop = pending[2]
                if not prop['accessors']:
                    pending[0]['properties'].append(prop)
                prop['accessors'].append(word)
    if active:
        raise ValueError(f'Unclosed type declarations: {path.name}')


def catalog(source, output):
    counts = Counter()
    assemblies = set()
    namespaces = set()
    with gzip.open(output / 'types.jsonl.gz', 'wt', encoding='utf-8') as full, \
            gzip.open(output / 'candidate-types.jsonl.gz', 'wt', encoding='utf-8') as focused:
        for file in sorted(source.glob('**/*.cs')):
            assembly = file.relative_to(source).parts[0]
            for record in parse_type_file(file, assembly):
                counts['types'] += 1
                assemblies.add(assembly)
                namespaces.add(record['namespace'])
                for member in ('fields', 'properties', 'methods', 'enum_values'):
                    counts[member] += len(record[member])
                row = json.dumps(record, ensure_ascii=False) + '\n'
                full.write(row)
                if TARGET.search(record['type']):
                    focused.write(row)
                    counts['candidate_types'] += 1
    if not counts['types']:
        raise ValueError('Cpp2IL emitted no usable type declarations')
    return {'counts': dict(counts), 'assemblies': sorted(assemblies), 'namespaces': sorted(namespaces)}


def run_tool(args, log, timeout, cwd):
    temp_log = cwd / (log.name + '.working')
    with temp_log.open('wb') as stream:
        try:
            result = {'exit_code': subprocess.run(args, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout, cwd=cwd).returncode}
        except subprocess.TimeoutExpired:
            result = {'exit_code': 124, 'reason': 'timeout'}
    with temp_log.open('rb') as stream:
        log.write_bytes(stream.read(2 * 1024 * 1024))
    result['log_truncated'] = temp_log.stat().st_size > log.stat().st_size
    temp_log.unlink()
    return result


def cpp_command(work, unity):
    return [CPP, '--force-binary-path', str(work / 'libil2cpp.so'),
            '--force-metadata-path', str(work / 'global-metadata.dat'), '--force-unity-version', unity,
            '--low-memory-mode', '--output-as', 'diffable-cs', '--output-to', str(work / 'cpp-output')]


def synthetic_fixture(work):
    fixture.verify()
    synth = work / 'fixture'
    synth.mkdir()
    top = synth / 'fixture.apkm'
    elf = bytearray(64)
    elf[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<H', elf, 18, 183)
    with ZipFile(synth / BASE, 'w') as base:
        base.writestr(METADATA, struct.pack('<II', 0xFAB11BAF, 39))
        base.writestr(MANAGERS, b'6000.3.22f1\0')
    with ZipFile(synth / SPLIT, 'w') as split:
        split.writestr(ELF, elf)
    with ZipFile(top, 'w') as bundle:
        for name in (BASE, SPLIT):
            bundle.write(synth / name, name)
    extracted = synth / 'extracted'
    extracted.mkdir()
    manifest = inspect(top, extracted)
    if manifest['metadata_version'] != 39 or manifest['unity'] != '6000.3.22f1':
        raise ValueError('Synthetic inspection failed')
    cs = synth / 'DiffableCs' / 'Fixture'
    cs.mkdir(parents=True)
    (cs / 'PokemonFixture.cs').write_text(
        'namespace Fixture;\npublic class PokemonFixture : IFixture\n{\n'
        '\tpublic int Count; //Field offset: 0x10\n\tpublic int Value\n\t{\n\t\tget;\n\t}\n'
        '\tpublic int Read(int i) { }\n\tpublic enum Color\n\t{\n\t\tRed = 1,\n\t}\n}\n', encoding='utf-8')
    summary = catalog(cs.parent, synth)
    expected = {'types': 2, 'fields': 1, 'properties': 1, 'methods': 1, 'enum_values': 1, 'candidate_types': 2}
    if summary['counts'] != expected:
        raise ValueError(f'Synthetic catalog counts differ: {summary["counts"]}')
    java = synth / 'Fixture.java'
    java.write_text('public class Fixture { public int read() { return 7; } }\n', encoding='utf-8')
    subprocess.run(['javac', str(java)], check=True, timeout=30, cwd=synth)
    jadx = run_tool([JADX, '-j', '1', '-d', str(synth / 'jadx'), str(synth / 'Fixture.class')],
                    OUT / 'fixture-jadx.log', 60, synth)
    if jadx['exit_code'] != 0 or not any((synth / 'jadx').rglob('Fixture.java')):
        raise ValueError('JADX failed the synthetic Java fixture')
    cpp = run_tool(cpp_command(extracted, manifest['unity']), OUT / 'fixture-cpp2il.log', 60, synth)
    if cpp['exit_code'] in (0, 124, 126, 127) or 'EndOfStreamException' not in (OUT / 'fixture-cpp2il.log').read_text(errors='replace'):
        raise ValueError(f'Cpp2IL did not reject the deliberately incomplete binary as expected: {cpp}')
    write_json(OUT / 'fixture.json', {'package_header': 'pass', 'metadata_v39_fixture': 'pass', 'catalog': summary,
                                    'jadx': jadx, 'cpp2il_negative_fixture': cpp})


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('action', choices=('inspect', 'catalog'))
    cli.add_argument('expected_sha256')
    args = cli.parse_args()
    with tempfile.TemporaryDirectory(prefix='game-research-') as scratch:
        work = Path(scratch)
        synthetic_fixture(work)
        if digest(GAME) != args.expected_sha256:
            raise ValueError('APKM SHA-256 does not match configured game input')
        manifest = inspect(GAME, work)
        write_json(OUT / 'package.json', manifest)
        if args.action == 'inspect':
            return
        result = {'provenance': manifest, 'cpp2il': run_tool(cpp_command(work, manifest['unity']), OUT / 'cpp2il.log', 420, work)}
        if result['cpp2il']['exit_code'] == 0:
            result['catalog'] = catalog(work / 'cpp-output' / 'DiffableCs', work)
        elif (manifest['metadata_version'] == 39
              and 'Failed to find code registration or metadata registration'
              in (OUT / 'cpp2il.log').read_text(encoding='utf-8', errors='replace')):
            result['catalog'] = metadata_v39.export(work / 'global-metadata.dat', work)
        if 'catalog' in result:
            for name in ('types.jsonl.gz', 'candidate-types.jsonl.gz'):
                if (work / name).stat().st_size > 128 * 1024 * 1024:
                    raise ValueError(f'Catalog exceeds export cap: {name}')
                shutil.copyfile(work / name, OUT / name)
        target = 'com.nianticlabs.nia.location.NianticLocationManager'
        result['jadx'] = run_tool(
            [JADX, '-j', '2', '--no-res', '--single-class', target, '--single-class-output',
             str(work / 'NianticLocationManager.java'), str(work / BASE)], OUT / 'jadx.log', 180, work)
        java = work / 'NianticLocationManager.java'
        if result['jadx']['exit_code'] == 0 and java.is_file() and java.stat().st_size <= 2 * 1024 * 1024:
            shutil.copyfile(java, OUT / java.name)
            result['jadx']['selected_class'] = target
        write_json(OUT / 'result.json', result)
        if 'catalog' not in result or 'selected_class' not in result['jadx']:
            raise SystemExit(1)


if __name__ == '__main__':
    main()
