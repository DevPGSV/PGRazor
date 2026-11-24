"""Read standard IL2CPP v39 definition tables, without resolving ELF registration.

Layout reference: LibCpp2IL Metadata definitions at tag 2022.1.0-pre-release.21 (MIT).
Type-table indices are resolved only when a definition declares the same by-value
index. External/composite references retain their indices without guessed names.
"""
from collections import Counter
import gzip
import json
import re
import struct

SECTIONS = (
    'stringLiteral stringLiteralData strings events properties methods parameterDefaults fieldDefaults '
    'defaultData marshaledSizes parameters fields genericParameters genericConstraints genericContainers '
    'nestedTypes interfaces vtableMethods interfaceOffsets types images assemblies fieldRefs '
    'referencedAssemblies attributeData attributeRanges virtualParameterTypes virtualParameterRanges '
    'windowsTypeNames windowsStrings exportedTypes'
).split()
HEADER = 8 + 12 * len(SECTIONS)
TARGET = re.compile(r'pokemon|pok[eé]dex|incubat|egg|itembag|inventory|storage|journal|player(stats|service|data|profile)|iapservice|stardust|currency', re.I)


def width(count):
    return 'B' if count <= 255 else 'H' if count <= 65535 else 'i'


def index(value, code):
    return None if value == {'B': 255, 'H': 65535, 'i': -1}[code] else value


class Metadata:
    def __init__(self, data):
        self.data = data
        if len(data) < HEADER or struct.unpack_from('<II', data) != (0xFAB11BAF, 39):
            raise ValueError('Expected a standard IL2CPP metadata v39 header')
        self.sections = {}
        for i, name in enumerate(SECTIONS):
            offset, size, count = struct.unpack_from('<iii', data, 8 + 12*i)
            if min(offset, size, count) < 0 or offset + size > len(data) or (size and offset < HEADER):
                raise ValueError(f'Invalid bounds for {name}')
            self.sections[name] = (offset, size, count)
        _, size, count = self.sections['interfaceOffsets']
        if count == 0 or size % count or size // count - 4 not in (1, 2, 4):
            raise ValueError('Cannot establish runtime type index width')
        self.ref = {1: 'B', 2: 'H', 4: 'i'}[size//count - 4]
        self.tdef = width(self.sections['types'][2])
        generic = width(self.sections['genericContainers'][2])
        parameter = width(self.sections['parameters'][2])
        self.parameter = parameter
        self.rows = {
            'types': self.layout('types', 'ii' + self.ref*3 + generic + 'I' + 'i'*8 + 'H'*8 + 'II'),
            'images': self.layout('images', 'ii' + self.tdef + 'I' + self.tdef + 'I' + 'iIiI'),
            'assemblies': self.layout('assemblies', 'iIIii' + 'iiiIiIiiiiQ'),
            'referencedAssemblies': self.layout('referencedAssemblies', 'i'),
            'methods': self.layout('methods', 'i' + self.tdef + self.ref + 'I' + parameter + generic + 'IHHHH'),
            'fields': self.layout('fields', 'i' + self.ref + 'I'),
            'parameters': self.layout('parameters', 'iI' + self.ref),
            'properties': self.layout('properties', 'iiiII'),
            'interfaces': self.layout('interfaces', self.ref),
            'nestedTypes': self.layout('nestedTypes', 'i'),
        }
        self.type_meta = [self.row('types', i) for i in range(self.sections['types'][2])]
        self.type_names = [(self.text(r[0]), self.text(r[1])) for r in self.type_meta]
        self.byval = {}
        for i, row in enumerate(self.type_meta):
            key = index(row[2], self.ref)
            if key is not None:
                self.byval[key] = i if key not in self.byval else None
        self.images = {}
        self.owners = {}
        for i in range(self.sections['images'][2]):
            name, assembly, first, count, *_ = self.row('images', i)
            label = self.text(name)
            self.images[i] = {'image': label, 'assembly_index': assembly, 'type_count': count}
            for type_i in self.span('types', index(first, self.tdef), count):
                if type_i in self.owners:
                    raise ValueError('Type appears in two images')
                self.owners[type_i] = label
        self.assemblies = []
        for i in range(self.sections['assemblies'][2]):
            row = self.row('assemblies', i)
            image, _, _, first, count, name = row[:6]
            refs = [self.row('referencedAssemblies', j)[0]
                    for j in self.span('referencedAssemblies', first, count)]
            if self.images.get(image, {}).get('assembly_index') != i or any(
                    r < 0 or r >= self.sections['assemblies'][2] for r in refs):
                raise ValueError('Invalid assembly/image relation or reference')
            self.assemblies.append({'index': i, 'name': self.text(name), 'image': self.images[image]['image'],
                                    'version': list(row[11:15]), 'referenced_assembly_indices': refs})

    def layout(self, name, fmt):
        layout = struct.Struct('<' + fmt)
        _, size, count = self.sections[name]
        if layout.size * count != size:
            raise ValueError(f'Unexpected {name} layout: {size} bytes / {count} rows, expected {layout.size} bytes per row')
        return layout

    def row(self, table, i):
        offset, _, count = self.sections[table]
        if not 0 <= i < count:
            raise ValueError(f'Invalid {table} row index')
        layout = self.rows[table]
        return layout.unpack_from(self.data, offset+i*layout.size)

    def span(self, table, first, count):
        if count == 0:
            return range(0)
        if first is None or first < 0 or first + count > self.sections[table][2]:
            raise ValueError(f'Invalid {table} range')
        return range(first, first+count)

    def text(self, i):
        start, size, _ = self.sections['strings']
        if not 0 <= i < size:
            raise ValueError('String index outside metadata string table')
        end = self.data.find(b'\0', start+i, min(start+size, start+i+8192))
        if end < 0:
            raise ValueError('Missing or oversized string terminator')
        return self.data[start+i:end].decode('utf-8')

    def reference(self, value):
        key = index(value, self.ref)
        if key is None:
            return None
        match = self.byval.get(key)
        result = {'type_index': key}
        if match is not None:
            name, namespace = self.type_names[match]
            result.update(name=name, namespace=namespace, image=self.owners.get(match), type_def_index=match)
        return result

    def method(self, i):
        name, owner, return_type, _, first, _, token, flags, _, _, count = self.row('methods', i)
        params = []
        for p in self.span('parameters', index(first, self.parameter), count):
            pname, _, ptype = self.row('parameters', p)
            params.append({'name': self.text(pname), 'type': self.reference(ptype)})
        return {'name': self.text(name), 'declaring_type_index': index(owner, self.tdef),
                'return_type': self.reference(return_type), 'parameters': params, 'flags': flags, 'token': token}

    def type_record(self, i):
        r = self.type_meta[i]
        name, namespace = self.type_names[i]
        kind = 'enum' if r[23] & 2 else 'interface' if r[6] & 32 else 'struct' if r[23] & 1 else 'class'
        fields = []
        for field in self.span('fields', r[7], r[17]):
            field_name, ref, token = self.row('fields', field)
            fields.append({'name': self.text(field_name), 'type': self.reference(ref), 'token': token})
        methods = [self.method(m) for m in self.span('methods', r[8], r[15])]
        properties = []
        for p in self.span('properties', r[10], r[16]):
            pname, getter, setter, flags, token = self.row('properties', p)
            properties.append({'name': self.text(pname), 'getter_ordinal': None if getter < 0 else getter,
                               'setter_ordinal': None if setter < 0 else setter, 'flags': flags, 'token': token,
                               'type': methods[getter]['return_type'] if 0 <= getter < len(methods) else None})
        interfaces = [self.reference(self.row('interfaces', j)[0])
                      for j in self.span('interfaces', r[12], r[21])]
        nested = [index(self.row('nestedTypes', j)[0], 'i')
                  for j in self.span('nestedTypes', r[11], r[19])]
        return {'type_def_index': i, 'image': self.owners.get(i), 'namespace': namespace, 'name': name,
                'kind': kind, 'token': r[24], 'parent': self.reference(r[4]), 'declaring_type': self.reference(r[3]),
                'interfaces': interfaces, 'nested_type_def_indices': nested, 'fields': fields,
                'properties': properties, 'methods': methods,
                'enum_members': [f['name'] for f in fields if f['name'] != 'value__'] if kind == 'enum' else []}


def export(path, output):
    metadata = Metadata(path.read_bytes())
    totals = Counter()
    namespaces = set()
    with gzip.open(output / 'types.jsonl.gz', 'wt', encoding='utf-8') as full, \
            gzip.open(output / 'candidate-types.jsonl.gz', 'wt', encoding='utf-8') as candidates:
        for i in range(len(metadata.type_meta)):
            record = metadata.type_record(i)
            namespaces.add(record['namespace'])
            totals['types'] += 1
            totals[record['kind']] += 1
            for member in ('fields', 'properties', 'methods', 'enum_members'):
                totals[member] += len(record[member])
            row = json.dumps(record, ensure_ascii=False, separators=(',', ':')) + '\n'
            full.write(row)
            if TARGET.search(record['name']):
                candidates.write(row)
                totals['candidate_types'] += 1
    return {'mode': 'metadata-v39-definitions', 'counts': dict(totals), 'namespaces': sorted(namespaces),
            'images': list(metadata.images.values()), 'assemblies': metadata.assemblies,
            'unresolved': ['ELF registrations', 'method addresses', 'field offsets', 'composite or unresolved type names',
                           'enumeration numeric values', 'runtime behavior']}
