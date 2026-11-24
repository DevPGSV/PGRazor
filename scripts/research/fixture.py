"""Original, redistributable synthetic IL2CPP v39 table records for local tests."""
import struct

import metadata_v39


def verify():
    strings = b'\0Fixture.dll\0Fixture\0PokemonFixture\0Count\0Read\0value\0'
    s = lambda name: strings.index(name.encode() + b'\0')
    tables = {
        'strings': (strings, 0),
        'interfaceOffsets': (struct.pack('<Bi', 0, 0), 1),
        'interfaces': (b'\x00', 1),
        'images': (struct.pack('<iiBIBIiIiI', s('Fixture.dll'), 0, 0, 1, 255, 0, -1, 1, -1, 0), 1),
        'assemblies': (struct.pack('<iIIii' + 'iiiIiIiiiiQ', 0, 0, 0, -1, 0, s('Fixture'),
                                   -1, -1, 0, 0, 0, 1, 0, 0, 0, 0), 1),
        'types': (struct.pack('<iiBBBBI8i8HII', s('PokemonFixture'), s('Fixture'), 0, 255, 255, 255, 0,
                              0, 0, -1, 0, -1, 0, -1, -1, 1, 1, 1, 0, 0, 0, 1, 0, 0, 0x02000001), 1),
        'fields': (struct.pack('<iBI', s('Count'), 0, 0x04000001), 1),
        'methods': (struct.pack('<iBBIBBIHHHH', s('Read'), 0, 0, 0, 0, 255, 0x06000001, 0, 0, 0, 1), 1),
        'parameters': (struct.pack('<iIB', s('value'), 0x08000001, 1), 1),
        'properties': (struct.pack('<iiiII', s('Count'), 0, -1, 0, 0x17000001), 1),
    }
    header = bytearray(struct.pack('<II', 0xFAB11BAF, 39))
    payload = bytearray()
    for name in metadata_v39.SECTIONS:
        data, count = tables.get(name, (b'', 0))
        header.extend(struct.pack('<iii', metadata_v39.HEADER+len(payload), len(data), count))
        payload.extend(data)
    binary = bytes(header + payload)
    metadata = metadata_v39.Metadata(binary)
    record = metadata.type_record(0)
    checks = (
        metadata.assemblies[0]['name'] == 'Fixture',
        record['image'] == 'Fixture.dll', record['namespace'] == 'Fixture',
        record['fields'][0]['type']['name'] == 'PokemonFixture',
        record['properties'][0]['getter_ordinal'] == 0,
        record['methods'][0]['parameters'][0]['type'] == {'type_index': 1},
        record['interfaces'][0]['type_def_index'] == 0,
    )
    if not all(checks):
        raise ValueError('Synthetic v39 definitions did not match expected records')
    try:
        metadata_v39.Metadata(binary[:-1])
    except ValueError:
        return
    raise ValueError('Truncated synthetic v39 fixture was accepted')
