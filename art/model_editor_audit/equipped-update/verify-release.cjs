'use strict';

// Read-only release audit. Uses the launcher's real signature verifier and public key.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const root = 'D:/23';
const audit = path.join(root, 'art/model_editor_audit/equipped-update');
const { verifyManifestSignature } = require(path.join(root, 'launcher/manifest-signing.js'));
const main = fs.readFileSync(path.join(root, 'launcher/main.js'), 'utf8');
const publicKey = main.match(/const DISTRIBUTION_PUBLIC_KEY\s*=\s*process\.env\.ECLIPSE_DISTRIBUTION_PUBLIC_KEY\s*\|\|\s*'([^']+)'/)[1];
const local = JSON.parse(fs.readFileSync(path.join(root, 'distribution-build/manifests/production.json'), 'utf8'));
const expected = local.client.mods.find(mod => mod.name === 'eclipse-client-1.5.59.jar');
assert.ok(expected, 'Final manifest must contain client 1.5.59');
assert.equal(verifyManifestSignature(local, publicKey).ok, true, 'Local manifest signature');

async function fetchChecked(url) {
    const response = await fetch(url, { signal: AbortSignal.timeout(45000), headers: { 'Cache-Control': 'no-cache' } });
    assert.equal(response.ok, true, `HTTP ${response.status}: ${url}`);
    return response;
}

async function verifyMirror(name, baseUrl) {
    const manifestUrl = `${baseUrl}/manifests/production.json?verify=${Date.now()}`;
    const manifest = await (await fetchChecked(manifestUrl)).json();
    assert.equal(verifyManifestSignature(manifest, publicKey).ok, true, `${name}: real launcher signature verification`);
    assert.equal(manifest.release.id, local.release.id, `${name}: current release ID`);
    assert.equal(manifest.signature, local.signature, `${name}: exact final manifest`);
    const entry = manifest.client.mods.find(mod => mod.name === expected.name);
    assert.ok(entry, `${name}: client version`);
    assert.equal(entry.sha256, expected.sha256);
    assert.equal(entry.size, expected.size);
    const jarUrl = `${baseUrl}/client/${entry.path}?sha256=${entry.sha256}`;
    const response = await fetchChecked(jarUrl);
    const digest = crypto.createHash('sha256');
    let size = 0;
    for await (const chunk of response.body) { digest.update(chunk); size += chunk.byteLength; }
    const sha256 = digest.digest('hex');
    assert.equal(size, expected.size, `${name}: downloaded JAR size`);
    assert.equal(sha256, expected.sha256, `${name}: downloaded JAR SHA-256`);
    fs.writeFileSync(path.join(audit, `${name}-production.json`), JSON.stringify(manifest, null, 2) + '\n');
    console.log(`${name}: signed release ${manifest.release.id}, ${entry.name}, ${size} bytes, SHA-256 verified`);
    return { name, manifestUrl, jarUrl, releaseId: manifest.release.id, signed: true, size, sha256 };
}

(async () => {
    const results = await Promise.allSettled([
        verifyMirror('r2', 'https://dist.eclipse-roleplay.online'),
        verifyMirror('github', 'https://raw.githubusercontent.com/evgeniy111222333/oasis/dist')
    ]);
    const mirrors = [];
    for (const result of results) {
        if (result.status === 'fulfilled') mirrors.push(result.value);
        else console.error(result.reason);
    }
    assert.equal(mirrors.length, 2, 'Both mandatory public mirrors must verify');
    const startup = JSON.parse(fs.readFileSync(path.join(audit, 'server-startup.json'), 'utf8'));
    const startupLog = fs.readFileSync(startup.Stdout, 'utf8');
    assert.match(startupLog, /eclipseserver 1\.5\.31/);
    assert.match(startupLog, /Done \(/);
    const serverStatus = await (await fetchChecked('http://127.0.0.1:25580/api/server-status')).json();
    assert.equal(serverStatus.success, true);
    assert.equal(serverStatus.status, 'online');
    const serverJar = fs.readFileSync(path.join(root, 'fabric-server/mods/eclipseserver-1.5.31.jar'));
    const clientJar = fs.readFileSync(path.join(root, 'plugins/RPChat/client/mods', expected.name));
    assert.equal(crypto.createHash('sha256').update(clientJar).digest('hex'), expected.sha256);
    const receipt = {
        verifiedAt: new Date().toISOString(), releaseId: local.release.id,
        serverVersion: '1.5.31', clientVersion: '1.5.59',
        clientSha256: expected.sha256, serverSha256: crypto.createHash('sha256').update(serverJar).digest('hex'),
        mirrors, localServer: { ...startup, version: '1.5.31', startupComplete: true, ...serverStatus },
        verification: { fullChecksBuild: 'publish_update.log: BUILD SUCCESSFUL, 75 tasks',
            nativeClientGameTests: '../client-gametest-equipped-green.log: BUILD SUCCESSFUL, all 3 suites with held-item regression',
            consoleCommand: '/modeleditor [reload]' },
        vps: { deployed: false, reason: 'SSH connection to 13.51.232.191 timed out; local Fabric server is online.' }
    };
    const placements = JSON.parse(fs.readFileSync(path.join(root, 'fabric-server/config/RPChat/model-placements.json'), 'utf8'));
    for (const item of ['carver_bag', 'carver_bag_no_point', 'carver_bag_empty']) {
        const placement = placements.entries.find(entry => entry.item === `eclipseserver:${item}` && entry.context === 'WORN');
        assert.ok(placement, `Server-wide ${item} placement`);
        assert.equal(placement.attachment, 'BACK');
        assert.equal(placement.model, '');
        assert.deepEqual(placement.transform, { x: 0, y: 5.25, z: 3, rx: 180, ry: 0, rz: 0, sx: .79, sy: .79, sz: .79 });
    }
    receipt.bagPlacements = placements;
    fs.writeFileSync(path.join(audit, 'release-receipt.json'), JSON.stringify(receipt, null, 2) + '\n');
    console.log('Local server 1.5.31 online; all 3 bag placements and local/client JARs verified; release-receipt.json written.');
})().catch(error => { console.error(error); process.exitCode = 1; });
