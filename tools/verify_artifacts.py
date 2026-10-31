#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Check the actual library and normal Forge test artifacts before handing them out."""
import argparse
from contextlib import ExitStack
import hashlib
import io
import json
from pathlib import Path
from zipfile import ZipFile

ENGINE_ID = ('org.voxelregion', 'voxel-region-engine')
ADAPTER_ID = ('org.voxelregion', 'voxel-region-forge-adapter-1.20.1')
ACCESS_TRANSFORMERS = 'META-INF/accesstransformer.cfg'
REMOVED_MARKERS = (b'ca/spottedleaf/', b'ca.spottedleaf.', b'vre-starlight',
                   b'META-INF/starlight/')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def manifest(archive):
    # The jar writer may wrap long manifest attributes on continuation lines.
    text = archive.read('META-INF/MANIFEST.MF').decode('utf8').replace('\r\n', '\n')
    text = text.replace('\n ', '')
    return dict(line.split(': ', 1) for line in text.splitlines() if ': ' in line)


def removed_component(archive, label):
    """Inspect content too: old classes can survive in refmaps or nested jars."""
    for name in archive.namelist():
        encoded = name.encode('utf8')
        require(not any(marker in encoded for marker in REMOVED_MARKERS),
                'Removed lighting component entry: ' + label + '!' + name)
        if name.endswith('/'):
            continue
        data = archive.read(name)
        require(not any(marker in data for marker in REMOVED_MARKERS),
                'Removed lighting reference: ' + label + '!' + name)
        if name.endswith('.jar'):
            with ZipFile(io.BytesIO(data)) as nested:
                removed_component(nested, label + '!' + name)
    # This standalone project has no source AT. A resource left by the removed
    # component must not be silently repackaged by an incremental build.
    require(ACCESS_TRANSFORMERS not in archive.namelist(),
            'Unexpected access transformer in standalone artifact: ' + label)


def build_cache(root):
    """Fail on stale build output; never delete user files from a verifier."""
    if not root.exists():
        return
    for path in root.rglob('*'):
        relative = path.relative_to(root).as_posix()
        require(not any(marker in relative.encode('utf8') for marker in REMOVED_MARKERS),
                'Removed lighting build output; run clean: ' + str(path))
        if not path.is_file():
            continue
        if path.suffix == '.jar':
            with ZipFile(path) as archive:
                removed_component(archive, str(path))
        elif path.suffix in ('.class', '.json', '.cfg', '.MF'):
            require(not any(marker in path.read_bytes() for marker in REMOVED_MARKERS),
                    'Removed lighting reference in build output; run clean: ' + str(path))
        require(not relative.endswith(ACCESS_TRANSFORMERS),
                'Stale standalone access transformer; run clean: ' + str(path))


def engine(archive):
    removed_component(archive, 'portable engine')
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
    parser.add_argument('--build-root', action='append', type=Path, default=[],
                        help='Additional standalone build output to check for stale removed-component files')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[1]
    for root in [repository / 'build', repository / 'tools/forge-test/build', *args.build_root]:
        build_cache(root)
    with ZipFile(args.core) as archive:
        engine(archive)
        core_classes = {name: archive.read(name) for name in archive.namelist() if name.endswith('.class')}
    with ExitStack() as stack:
        archive = stack.enter_context(ZipFile(args.forge))
        removed_component(archive, str(args.forge))
        names = archive.namelist()
        require('org/voxelregion/testmod/RegionChargeItem.class' in names, 'Missing adjustable charge')
        require(not any('/geometry/' in n for n in names), 'Engine duplicated outside nested jar')
        metadata = json.loads(archive.read('META-INF/jarjar/metadata.json'))
        libraries = metadata['jars']
        identities = [(entry['identifier']['group'], entry['identifier']['artifact']) for entry in libraries]
        require(len(libraries) == 2 and set(identities) == {ENGINE_ID, ADAPTER_ID},
                'JarJar identities must match the GTLCore embedding, not Gradle subproject aliases')
        paths = [entry['path'] for entry in libraries]
        require(len(paths) == len(set(paths)), 'Multiple JarJar entries point to the same archive')
        require(all(path.startswith(('META-INF/jarjar/', 'META-INF/jars/')) and path in names for path in paths),
                'Missing or misplaced nested JarJar archive')
        required_configs = {'voxelregion-forge.mixins.json', 'voxelregion-receipts.mixins.json'}
        require(required_configs <= set(manifest(archive).get('MixinConfigs', '').split(',')),
                'Test mod manifest does not activate the adapter mixins')
        found_engine = found_adapter = 0
        classes = set()
        archives = [(args.forge.name, archive, None)]
        for entry in libraries:
            part = stack.enter_context(ZipFile(io.BytesIO(archive.read(entry['path']))))
            identity = (entry['identifier']['group'], entry['identifier']['artifact'])
            archives.append((entry['path'], part, identity))
        for label, part, identity in archives:
            part_names = part.namelist()
            require(len(part_names) == len(set(part_names)), 'Duplicate ZIP entries: ' + label)
            require(not any('GameTests' in n or 'ProbeBlocks' in n or n.endswith('/empty.nbt')
                            or '/dimension/generation_' in n
                            or n == 'data/voxelregion_test/loot_tables/chests/verification_counted_container.json'
                            for n in part_names),
                    'Verification-only content leaked: ' + label)
            require(not any('.local/' in n or n.startswith(('logs/', 'world/'))
                            or (not n.endswith(('.class', '/')) and ('/logs/' in n or '/world/' in n))
                            for n in part_names), 'Local evidence leaked: ' + label)
            this_classes = {n for n in part_names if n.endswith('.class')}
            require(not (classes & this_classes), 'Duplicate classes across Forge modules: ' + label)
            classes.update(this_classes)
            if 'org/voxelregion/geometry/Volume.class' in part_names:
                require(identity == ENGINE_ID, 'Engine is outside its independent nested library')
                found_engine += 1
                engine(part)
                require({n: part.read(n) for n in this_classes} == core_classes,
                        'Nested engine differs from standalone library')
            if 'org/voxelregion/forge/ForgeRegionEngine.class' in part_names:
                require(identity == ADAPTER_ID, 'Adapter is outside its independent nested library')
                found_adapter += 1
                require('META-INF/mods.toml' not in part_names, 'Adapter must be a library, not another gameplay mod')
                require('FMLModType: GAMELIBRARY' in part.read('META-INF/MANIFEST.MF').decode('utf8'), 'Adapter discovery metadata missing')
                for config in ('voxelregion-forge.mixins.json', 'voxelregion-receipts.mixins.json'):
                    spec = json.loads(part.read(config))
                    require('.mixin' in spec['package'], 'Mixin package also owns ordinary runtime classes')
                    require(spec['refmap'] in part_names, 'Missing production refmap: ' + config)
                if args.adapter:
                    with ZipFile(args.adapter) as standalone:
                        removed_component(standalone, str(args.adapter))
                        require({n: part.read(n) for n in this_classes} ==
                                {n: standalone.read(n) for n in standalone.namelist() if n.endswith('.class')},
                                'Nested adapter differs from standalone remapped adapter')
        require(found_engine == found_adapter == 1, 'Missing or duplicated nested library')
    artifacts = [p for p in (args.core, args.forge, args.adapter) if p]
    result = {'result': 'PASS', 'core': str(args.core.resolve()), 'forge': str(args.forge.resolve()),
              'sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in artifacts},
              'nested_libraries': libraries}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
