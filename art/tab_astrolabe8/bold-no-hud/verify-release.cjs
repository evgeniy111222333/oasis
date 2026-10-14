'use strict';

// Read-only audit of the exact public artifacts consumed by the launcher.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const root = 'D:/23';
const audit = path.join(root, 'art/tab_astrolabe8/bold-no-hud');
const { verifyManifestSignature } = require(path.join(root, 'launcher/manifest-signing.js'));
const main = fs.readFileSync(path.join(root, 'launcher/main.js'), 'utf8');
const publicKey = main.match(/const DISTRIBUTION_PUBLIC_KEY\s*=\s*process\.env\.ECLIPSE_DISTRIBUTION_PUBLIC_KEY\s*\|\|\s*'([^']+)'/)[1];
const local = JSON.parse(fs.readFileSync(path.join(root, 'distribution-build/manifests/production.json'), 'utf8'));
const expected = local.client.mods.find(mod => mod.name === 'eclipse-client-1.5.61.jar');
assert.ok(expected, 'Final manifest must contain client 1.5.61');
assert.equal(verifyManifestSignature(local, publicKey).ok, true, 'Local manifest signature');

async function fetchChecked(url) {
    const response = await fetch(url, { signal: AbortSignal.timeout(90000), headers: { 'Cache-Control': 'no-cache' } });
    assert.equal(response.ok, true, `HTTP ${response.status}: ${url}`);
    return response;
}

async function verifyMirror(name, baseUrl) {
    const manifestUrl = `${baseUrl}/manifests/production.json?verify=${Date.now()}`;
    const manifest = await (await fetchChecked(manifestUrl)).json();
    assert.equal(verifyManifestSignature(manifest, publicKey).ok, true, `${name}: launcher's real signature verification`);
    assert.equal(manifest.release.id, local.release.id, `${name}: current release ID`);
    assert.equal(manifest.signature, local.signature, `${name}: exact final signed manifest`);
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
    for (const distributionRoot of ['plugins/RPChat/client', 'fabric-server/plugins/RPChat/client', 'fabric-server/config/RPChat/client']) {
        const jar = fs.readFileSync(path.join(root, distributionRoot, 'mods', expected.name));
        assert.equal(crypto.createHash('sha256').update(jar).digest('hex'), expected.sha256, distributionRoot);
    }
    const native = fs.readFileSync(path.join(audit, 'client-native.log'), 'utf8');
    const publish = fs.readFileSync(path.join(audit, 'publish_update.log'), 'utf8');
    assert.match(native, /BUILD SUCCESSFUL/);
    assert.match(native, /TabTextRasterTest: smooth Cyrillic\/Latin edges/);
    assert.match(publish, /BUILD SUCCESSFUL/);
    assert.match(publish, /Published release push/);
    fs.writeFileSync(path.join(audit, 'release-receipt.json'), JSON.stringify({
        verifiedAt: new Date().toISOString(), releaseId: local.release.id, clientVersion: '1.5.61',
        clientSha256: expected.sha256, size: expected.size, mirrors,
        verification: { nativeClientGameTests: 'client-native.log',
            fullClientChecksAndBuild: 'publish_update.log', screenshots: 'native-final/',
            text: 'Bold Palatino names/header, 4x antialiased raster, GPU linear filtering, centered header; custom bottom HUD and stamina gauge removed',
            viewports: ['1280x720', '1920x1080', '3840x2160'], maxTestRoster: 1000 },
        serverProtocolChanged: false
    }, null, 2) + '\n');
    console.log('All three local client copies and both mandatory public mirrors match; release-receipt.json written.');
})().catch(error => { console.error(error); process.exitCode = 1; });
