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
    parser.add_argument('--adapter', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    with ZipFile(args.core) as archive:
        engine(archive)
        core_classes = {name: archive.read(name) for name in archive.namelist() if name.endswith('.class')}
    with ZipFile(args.forge) as archive:
        names = archive.namelist()
        require('org/voxelregion/testmod/RegionChargeItem.class' in names, 'Missing adjustable charge')
        require(not any('/geometry/' in n for n in names), 'Engine duplicated outside nested jar')
        metadata = json.loads(archive.read('META-INF/jarjar/metadata.json'))
        libraries = [entry for entry in metadata['jars'] if entry['identifier']['group'] == 'org.voxelregion']
        require(len(libraries) == 2, 'Expected independent nested engine and Forge adapter')
        require({entry['identifier']['artifact'] for entry in libraries} ==
                {'voxel-region-engine', 'voxel-region-forge-adapter-1.20.1'},
                'JarJar identities must match the GTLCore embedding, not Gradle subproject aliases')
        found_engine = found_adapter = 0
        classes = set()
        archives = [(args.forge.name, archive)]
        for entry in libraries:
            archives.append((entry['path'], ZipFile(io.BytesIO(archive.read(entry['path'])))))
        for label, part in archives:
            part_names = part.namelist()
            require(not any('GameTests' in n or 'ProbeBlocks' in n or n.endswith('/empty.nbt')
                            or '/dimension/generation_' in n
                            or n == 'data/voxelregion_test/loot_tables/chests/verification_counted_container.json'
                            for n in part_names),
                    'Verification-only content leaked: ' + label)
            require(not any('.local/' in n or '/logs/' in n or '/world/' in n for n in part_names), 'Local evidence leaked: ' + label)
            this_classes = {n for n in part_names if n.endswith('.class')}
            require(not (classes & this_classes), 'Duplicate classes across Forge modules: ' + label)
            classes.update(this_classes)
            if 'org/voxelregion/geometry/Volume.class' in part_names:
                found_engine += 1
                engine(part)
                require({n: part.read(n) for n in this_classes} == core_classes,
                        'Nested engine differs from standalone library')
            if 'org/voxelregion/forge/ForgeRegionEngine.class' in part_names:
                found_adapter += 1
                require('META-INF/mods.toml' not in part_names, 'Adapter must be a library, not another gameplay mod')
                require('FMLModType: GAMELIBRARY' in part.read('META-INF/MANIFEST.MF').decode('utf8'), 'Adapter discovery metadata missing')
                for config in ('voxelregion-forge.mixins.json', 'voxelregion-receipts.mixins.json'):
                    spec = json.loads(part.read(config))
                    require('.mixin' in spec['package'], 'Mixin package also owns ordinary runtime classes')
                    require(spec['refmap'] in part_names, 'Missing production refmap: ' + config)
                if args.adapter:
                    with ZipFile(args.adapter) as standalone:
                        require({n: part.read(n) for n in this_classes} ==
                                {n: standalone.read(n) for n in standalone.namelist() if n.endswith('.class')},
                                'Nested adapter differs from standalone remapped adapter')
        require(found_engine == found_adapter == 1, 'Missing or duplicated nested library')
    result = {'result': 'PASS', 'core': str(args.core.resolve()), 'forge': str(args.forge.resolve()),
              'sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in (args.core, args.forge)},
              'nested_libraries': libraries}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
