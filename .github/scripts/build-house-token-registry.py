#!/usr/bin/env python3
"""Generate the versioned HOUSE catalog from every bundled Forge token script."""
import json
import hashlib
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE_TYPES = {'Creature', 'Artifact', 'Enchantment', 'Land', 'Planeswalker', 'Instant',
              'Sorcery', 'Kindred', 'Tribal', 'Battle', 'Dungeon', 'Emblem'}
SUPERTYPES = {'Legendary', 'Basic', 'Snow', 'World', 'Token'}

def generate():
    paths = sorted((ROOT / 'forge-gui/res/tokenscripts').glob('*.txt'))
    if not paths:
        raise ValueError('Forge token scripts missing; refusing to build an empty catalog')
    result = []
    for path in paths:
        text = path.read_text(encoding='utf-8-sig')
        fields = {}
        keywords = []
        for line in text.splitlines():
            if ':' not in line or line.startswith('#'):
                continue
            key, value = line.split(':', 1)
            fields.setdefault(key, value.strip())
            if key == 'K':
                keywords.append(value.strip())
        if not fields.get('Name'):
            raise ValueError(f'Token lacks a name: {path}')
        typeline = fields.get('Types', '').replace('—', ' ').split()
        pt = fields.get('PT', '/').split('/', 1)
        if len(pt) != 2:
            raise ValueError(f'Invalid P/T: {path}')
        result.append(dict(tokenId=path.stem, name=fields['Name'],
            types=[t for t in typeline if t in CORE_TYPES or t in SUPERTYPES],
            subtypes=[t for t in typeline if t not in CORE_TYPES and t not in SUPERTYPES],
            power=pt[0], toughness=pt[1], keywords=keywords,
            colors=re.findall(r'[A-Za-z]+', fields.get('Colors', 'colorless')),
            artProfile='house-vector:' + path.stem,
            animationProfile='flying' if 'Flying' in keywords else 'permanent',
            oracle=fields.get('Oracle', '')))
    data = (json.dumps(result, indent=2, ensure_ascii=False) + '\n').encode()
    out = ROOT / 'forge-gui-android/assets/tokens'
    out.mkdir(parents=True, exist_ok=True)
    (out / 'token-registry.json').write_bytes(data)
    (out / 'token-registry.id').write_text(hashlib.sha256(data).hexdigest() + '\n')
    print(f'HOUSE_TOKEN_CATALOG_PASS {len(paths)} scripts -> {len(result)} definitions')
    return result

if __name__ == '__main__':
    generate()
