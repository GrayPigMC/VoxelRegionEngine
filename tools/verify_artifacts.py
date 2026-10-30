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
LIGHTING_ID = ('ca.spottedleaf.starlight', 'vre-starlight-forge-1.20.1')
LIGHTING_CONFIG = 'vre-starlight.mixins.json'
ACCESS_TRANSFORMERS = 'META-INF/accesstransformer.cfg'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def manifest(archive):
    # The jar writer may wrap long manifest attributes on continuation lines.
    text = archive.read('META-INF/MANIFEST.MF').decode('utf8').replace('\r\n', '\n')
    text = text.replace('\n ', '')
    return dict(line.split(': ', 1) for line in text.splitlines() if ': ' in line)


def access_transformers(archive):
    return {' '.join(line.split('#', 1)[0].split())
            for line in archive.read(ACCESS_TRANSFORMERS).decode('utf8').splitlines()
            if line.split('#', 1)[0].strip()}


def lighting(archive, parent):
    names = set(archive.namelist())
    required = {'ca/spottedleaf/starlight/common/light/StarLightEngine.class',
                'ca/spottedleaf/starlight/common/light/StarLightInterface.class',
                'ca/spottedleaf/starlight/common/light/SWMRNibbleArray.class',
                LIGHTING_CONFIG, ACCESS_TRANSFORMERS,
                'META-INF/starlight/LICENSE', 'META-INF/starlight/NOTICE',
                'META-INF/starlight/UPSTREAM.json'}
    require(required <= names, 'Missing global lighting entries: ' + str(sorted(required - names)))
    require('META-INF/mods.toml' not in names, 'Lighting component must not register another gameplay mod')
    for name in names:
        if name.endswith('.class'):
            require(name.startswith('ca/spottedleaf/starlight/'), 'Foreign class in LGPL component: ' + name)
    attributes = manifest(archive)
    require(attributes.get('FMLModType') == 'GAMELIBRARY', 'Lighting library discovery metadata missing')
    require(attributes.get('Automatic-Module-Name') == 'ca.spottedleaf.starlight', 'Wrong lighting module name')
    require(LIGHTING_CONFIG in attributes.get('MixinConfigs', '').split(','), 'Lighting Mixin manifest entry missing')
    spec = json.loads(archive.read(LIGHTING_CONFIG))
    require(spec['package'] == 'ca.spottedleaf.starlight.mixin', 'Wrong lighting Mixin package')
    require(spec.get('required') is True, 'Global lighting mixins must be required')
    require(spec.get('refmap') in names, 'Lighting production refmap is missing')
    for mixin in spec.get('mixins', []) + spec.get('client', []) + spec.get('server', []):
        require((spec['package'] + '.' + mixin).replace('.', '/') + '.class' in names,
                'Missing lighting mixin class: ' + mixin)
    require(spec.get('plugin', '').replace('.', '/') + '.class' in names, 'Lighting Mixin plugin is missing')
    refmap = json.loads(archive.read(spec['refmap']))
    mappings = refmap.get('mappings', {})
    require(bool(mappings), 'Lighting production refmap has no mappings')
    for namespace, entries in [('mappings', mappings)] + list(refmap.get('data', {}).items()):
        require(all(key.startswith('ca/spottedleaf/starlight/') for key in entries),
                'Foreign mixin mappings in lighting refmap: ' + namespace)
    required_at = access_transformers(archive)
    require(bool(required_at), 'Lighting access transformer is empty')
    require(ACCESS_TRANSFORMERS in parent.namelist(), 'Host must expose lighting access transformers')
    require(required_at <= access_transformers(parent), 'Host access transformers omit lighting rules')
    require(b'GNU LESSER GENERAL PUBLIC LICENSE' in archive.read('META-INF/starlight/LICENSE'),
            'Lighting component is missing its LGPL license text')
    require(bool(archive.read('META-INF/starlight/NOTICE').strip()), 'Lighting NOTICE is empty')
    provenance = json.loads(archive.read('META-INF/starlight/UPSTREAM.json'))
    require(provenance.get('license') == 'LGPL-3.0-only', 'Wrong lighting provenance license')
    require(provenance.get('repository') == 'https://github.com/PaperMC/Starlight', 'Missing Starlight provenance')
    commit = provenance.get('commit', '')
    require(len(commit) == 40 and all(c in '0123456789abcdef' for c in commit), 'Missing pinned upstream commit')


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
    parser.add_argument('--starlight', type=Path, help='Optional standalone remapped LGPL component to compare')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    with ZipFile(args.core) as archive:
        engine(archive)
        core_classes = {name: archive.read(name) for name in archive.namelist() if name.endswith('.class')}
    with ExitStack() as stack:
        archive = stack.enter_context(ZipFile(args.forge))
        names = archive.namelist()
        require('org/voxelregion/testmod/RegionChargeItem.class' in names, 'Missing adjustable charge')
        require(not any('/geometry/' in n for n in names), 'Engine duplicated outside nested jar')
        metadata = json.loads(archive.read('META-INF/jarjar/metadata.json'))
        libraries = metadata['jars']
        identities = [(entry['identifier']['group'], entry['identifier']['artifact']) for entry in libraries]
        require(len(libraries) == 3 and set(identities) == {ENGINE_ID, ADAPTER_ID, LIGHTING_ID},
                'JarJar identities must match the GTLCore embedding, not Gradle subproject aliases')
        paths = [entry['path'] for entry in libraries]
        require(len(paths) == len(set(paths)), 'Multiple JarJar entries point to the same archive')
        require(all(path.startswith(('META-INF/jarjar/', 'META-INF/jars/')) and path in names for path in paths),
                'Missing or misplaced nested JarJar archive')
        require(LIGHTING_CONFIG in manifest(archive).get('MixinConfigs', '').split(','),
                'Test mod manifest does not activate global lighting mixins')
        found_engine = found_adapter = found_lighting = 0
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
                        require({n: part.read(n) for n in this_classes} ==
                                {n: standalone.read(n) for n in standalone.namelist() if n.endswith('.class')},
                                'Nested adapter differs from standalone remapped adapter')
            if identity == LIGHTING_ID:
                found_lighting += 1
                lighting(part, archive)
                if args.starlight:
                    require(archive.read(label) == args.starlight.read_bytes(),
                            'Nested lighting component differs from standalone remapped component')
            elif any(name.startswith('ca/spottedleaf/starlight/') for name in this_classes):
                raise ValueError('Lighting classes escaped the separate LGPL component: ' + label)
        require(found_engine == found_adapter == found_lighting == 1, 'Missing or duplicated nested library')
    artifacts = [p for p in (args.core, args.forge, args.adapter, args.starlight) if p]
    result = {'result': 'PASS', 'core': str(args.core.resolve()), 'forge': str(args.forge.resolve()),
              'sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in artifacts},
              'nested_libraries': libraries}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
