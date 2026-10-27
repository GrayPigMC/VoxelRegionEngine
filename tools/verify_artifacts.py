#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Check the actual library and normal Forge test artifacts before handing them out."""
import argparse
import hashlib
import io
import json
from pathlib import Path
from zipfile import ZipFile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def engine(archive):
    names = archive.namelist()
    require('org/voxelregion/geometry/Volume.class' in names, 'Missing engine geometry')
    require('org/voxelregion/planner/SectionPlanner.class' in names, 'Missing engine planner')
    require('META-INF/LICENSE' in names and 'META-INF/NOTICE' in names, 'Missing engine licensing')
    manifest = archive.read('META-INF/MANIFEST.MF').decode('utf8')
    require('FMLModType: GAMELIBRARY' in manifest, 'Forge must discover the nested Java library')
    require('Automatic-Module-Name: org.voxelregion.engine' in manifest, 'Wrong engine module name')
    for name in names:
        if name.endswith('.class'):
            require(name.startswith('org/voxelregion/'), 'Foreign class in portable engine: ' + name)
            data = archive.read(name)
            require(b'net/minecraft/' not in data and b'net/minecraftforge/' not in data,
                    'Minecraft dependency leaked into engine: ' + name)
            require('/testmod/' not in name and not name.endswith('Test.class'), 'Test class in library: ' + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--core', required=True, type=Path)
    parser.add_argument('--forge', required=True, type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    with ZipFile(args.core) as archive:
        engine(archive)
        core_classes = {name: archive.read(name) for name in archive.namelist() if name.endswith('.class')}
    with ZipFile(args.forge) as archive:
        names = archive.namelist()
        require('org/voxelregion/testmod/RegionChargeItem.class' in names, 'Missing adjustable charge')
        require(not any('RegionGameTests' in n or 'ProbeBlocks' in n or n.endswith('/empty.nbt') for n in names),
                'Verification-only content leaked into distributable mod')
        require(not any('/geometry/' in n for n in names), 'Engine duplicated outside nested jar')
        metadata = json.loads(archive.read('META-INF/jarjar/metadata.json'))
        libraries = [entry for entry in metadata['jars'] if entry['identifier']['group'] == 'org.voxelregion']
        require(len(libraries) == 1, 'Expected exactly one nested engine')
        with ZipFile(io.BytesIO(archive.read(libraries[0]['path']))) as nested:
            engine(nested)
            nested_classes = {name: nested.read(name) for name in nested.namelist() if name.endswith('.class')}
            require(nested_classes == core_classes, 'Nested engine differs from the verified standalone library')
        require(not any('.local/' in n or '/logs/' in n or '/world/' in n for n in names), 'Local evidence leaked')
    result = {'result': 'PASS', 'core': str(args.core.resolve()), 'forge': str(args.forge.resolve()),
              'sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in (args.core, args.forge)},
              'nested_engine': libraries}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
