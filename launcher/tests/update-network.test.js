'use strict';

const assert = require('assert');
const fs = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');
const {
    compareVersions,
    downloadFileAtomic,
    downloadFileWithFallback,
    fetchDistributionManifest,
    isValidDistributionManifest,
    readManifestCache,
    uniqueUrls,
    writeManifestCache
} = require('../update-network');

function manifest(generatedAt, clientName = 'eclipse-client-1.2.6.jar') {
    return {
        generatedAt,
        release: { id: generatedAt, publishedAt: generatedAt },
        launcher: { version: '1.0.14' },
        client: {
            mods: [{
                name: clientName,
                path: `mods/${clientName}`,
                size: 7,
                sha1: '0123456789abcdef0123456789abcdef01234567'
            }]
        }
    };
}

async function main() {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'eclipse-update-network-'));
    const logs = [];
    const log = line => logs.push(line);
    const newest = manifest('2026-07-13T19:30:00Z');
    const older = manifest('2026-07-12T19:30:00Z', 'eclipse-client-1.1.1.jar');
    const payload = Buffer.from('healthy-payload');

    const server = http.createServer((request, response) => {
        if (request.url.startsWith('/file')) {
            response.writeHead(200, { 'Content-Length': payload.length });
            response.end(payload);
            return;
        }
        if (request.url.startsWith('/stall')) {
            response.writeHead(200, { 'Content-Length': 100 });
            response.write('x');
            return;
        }
        if (request.url.startsWith('/newer-manifest')) {
            const body = Buffer.from(JSON.stringify(newest));
            response.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': body.length });
            response.end(body);
            return;
        }
        if (request.url.startsWith('/older-manifest')) {
            const body = Buffer.from(JSON.stringify(older));
            response.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': body.length });
            response.end(body);
            return;
        }
        response.writeHead(404);
        response.end('missing');
    });

    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const base = `http://127.0.0.1:${server.address().port}`;
    try {
        assert.strictEqual(isValidDistributionManifest(newest), true);
        assert.strictEqual(isValidDistributionManifest({ client: { mods: [] } }), false);
        assert.deepStrictEqual(uniqueUrls(['a', 'a', '', 'b']), ['a', 'b']);
        assert.strictEqual(compareVersions('1.0.14', '1.0.13'), 1);
        assert.strictEqual(compareVersions('1.0.13', '1.0.14'), -1);
        assert.strictEqual(compareVersions('1.0.14', '1.0.14'), 0);

        const destination = path.join(root, 'client.jar');
        fs.writeFileSync(destination, 'last-known-good', 'utf8');
        await assert.rejects(
            downloadFileAtomic(`${base}/stall`, destination, null, 100, log),
            /timed out|aborted/i
        );
        assert.strictEqual(fs.readFileSync(destination, 'utf8'), 'last-known-good');
        assert.strictEqual(fs.readdirSync(root).some(name => name.includes('.part-')), false);
        assert.strictEqual(logs.some(line => line.includes('Committed atomically') && line.includes('/stall')), false);

        const chosen = await downloadFileWithFallback(
            [`${base}/missing`, `${base}/file`], destination, null, 500, log, 1
        );
        assert.strictEqual(chosen, `${base}/file`);
        assert.deepStrictEqual(fs.readFileSync(destination), payload);

        const cachePath = path.join(root, 'distribution-cache.json');
        writeManifestCache(cachePath, newest, 'new-cache');
        assert.strictEqual(readManifestCache(cachePath).manifest.client.mods[0].name, 'eclipse-client-1.2.6.jar');

        const raceCachePath = path.join(root, 'race-cache.json');
        const raceStarted = Date.now();
        const raced = await fetchDistributionManifest({
            urls: [`${base}/stall-json`, `${base}/newer-manifest`],
            cachePath: raceCachePath,
            timeoutMs: 1000,
            log
        });
        assert.strictEqual(raced.cached, false);
        assert(Date.now() - raceStarted < 700, 'A stalled mirror delayed a healthy mirror');

        const downgradeAttempt = await fetchDistributionManifest({
            urls: [`${base}/older-manifest`], cachePath, timeoutMs: 500, log
        });
        assert.strictEqual(downgradeAttempt.cached, true);
        assert.strictEqual(downgradeAttempt.manifest.client.mods[0].name, 'eclipse-client-1.2.6.jar');

        const offlineAttempt = await fetchDistributionManifest({
            urls: [`${base}/missing`], cachePath, timeoutMs: 500, log
        });
        assert.strictEqual(offlineAttempt.cached, true);
        assert.strictEqual(offlineAttempt.manifest.client.mods[0].name, 'eclipse-client-1.2.6.jar');

        console.log('update-network.test: atomic downloads, mirror fallback, cache and downgrade protection passed');
    } finally {
        await new Promise(resolve => server.close(resolve));
        fs.rmSync(root, { recursive: true, force: true });
    }
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
