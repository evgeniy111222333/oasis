"""Verify the published signed manifest and actual client bytes; never starts Minecraft."""
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
PUBLIC_KEY = 'PBxj9O40kIzFg4kqIEg3DDmdGSCjqMdp2FURmfhay/M='
MIRRORS = {
    'r2': 'https://dist.eclipse-roleplay.online',
    'github': 'https://raw.githubusercontent.com/evgeniy111222333/oasis/4210d6d',
    'vps': 'https://api.eclipse-roleplay.online/dist',
}


def fetch(url):
    if '/manifests/' in url:
        url += ('&' if '?' in url else '?') + 't=' + str(time.time_ns())
    request = urllib.request.Request(url, headers={
        'User-Agent': 'Mozilla/5.0 Eclipse-Release-Verification', 'Cache-Control': 'no-cache',
    })
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.read()


def verify(name, base, expected):
    try:
        release_id = expected['release']['id']
        manifest_bytes = fetch(base + '/manifests/production.json?verify=' + release_id)
        manifest = json.loads(manifest_bytes)
        if manifest['release']['id'] != release_id:
            raise ValueError('Mirror still serves another release: ' + manifest['release']['id'])
        path = HERE / ('published-manifest-' + name + '.json')
        path.write_bytes(manifest_bytes)
        verifier = (
            "const fs=require('fs'); const v=require(process.argv[1]);"
            "const m=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));"
            "const r=v.verifyManifestSignature(m,process.argv[3]);"
            "if(!r.ok){throw new Error(r.reason)}"
        )
        subprocess.run(['node', '-e', verifier, str(ROOT / 'launcher/manifest-signing.js'),
                        str(path), PUBLIC_KEY], check=True, capture_output=True, text=True)
        target = next(m for m in manifest['client']['mods'] if m['name'] == 'eclipse-client-1.5.67.jar')
        local = next(m for m in expected['client']['mods'] if m['name'] == target['name'])
        if target['sha256'] != local['sha256'] or target['size'] != local['size']:
            raise ValueError('Published client descriptor does not match the tested artifact')
        data = fetch(base + '/client/' + target['path'])
        if len(data) != target['size'] or hashlib.sha256(data).hexdigest() != target['sha256']:
            raise ValueError('Downloaded client bytes do not match the signed descriptor')
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            metadata = json.loads(archive.read('fabric.mod.json'))
            if metadata['id'] != 'eclipseclient' or metadata['version'] != '1.5.67':
                raise ValueError('Wrong client identity/version in the downloaded JAR')
            for member in ('FirstPersonCameraAnchor', 'PlayerAnimationSampler'):
                if 'ua/rp/chat/client/animation/' + member + '.class' not in archive.namelist():
                    raise ValueError('Camera fix missing from published JAR: ' + member)
        return {'mirror': name, 'ok': True, 'release': release_id,
                'client': target['name'], 'signature': 'valid',
                'size': len(data), 'sha256': target['sha256']}
    except Exception as error:
        return {'mirror': name, 'ok': False, 'error': str(error)}


def main():
    expected = json.loads((ROOT / 'distribution-build/manifests/production.json').read_text(encoding='utf-8'))
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(verify, name, base, expected) for name, base in MIRRORS.items()]
        report = [future.result() for future in futures]
    (HERE / 'public-verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))
    if any(not item['ok'] for item in report if item['mirror'] in ('r2', 'github')):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
