'use strict';

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const {
    MirrorHealthStore,
    SUCCESS_TTL_MS,
    RECORD_TTL_MS,
    FAILURE_COOLDOWNS_MS,
    computeNetworkFingerprint
} = require('../mirror-health');

const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'eclipse-mirror-health-'));
const cachePath = path.join(tempRoot, 'health.json');
let now = 1_000_000;
let network = 'network-a';
const store = new MirrorHealthStore({
    cachePath,
    now: () => now,
    fingerprintProvider: () => network
});
const r2 = 'https://dist.eclipse-roleplay.online/client/file.jar';
const github = 'https://raw.githubusercontent.com/evgeniy111222333/oasis/dist/client/file.jar';

store.recordSuccess(github, { bytes: 1024 * 1024, durationMs: 1000 });
assert.strictEqual(store.hasFreshPreferred([github]), true);
assert.strictEqual(store.rank([r2, github])[0], github);
const reloadedStore = new MirrorHealthStore({
    cachePath,
    now: () => now,
    fingerprintProvider: () => network
});
assert.strictEqual(reloadedStore.rank([r2, github])[0], github, 'a successful mirror must survive a launcher restart');

now += SUCCESS_TTL_MS + 1;
assert.strictEqual(store.hasFreshPreferred([github]), false, 'a preferred mirror must be re-evaluated after six hours');

store.recordFailure(r2, new Error('timeout one'));
let record = store.getRecord(r2);
assert.strictEqual(record.retryAfter - now, FAILURE_COOLDOWNS_MS[0]);
store.recordFailure(r2, new Error('timeout two'));
record = store.getRecord(r2);
assert.strictEqual(record.retryAfter - now, FAILURE_COOLDOWNS_MS[1]);
store.recordFailure(r2, new Error('timeout three'));
record = store.getRecord(r2);
assert.strictEqual(record.retryAfter - now, FAILURE_COOLDOWNS_MS[2]);

network = 'network-b';
assert.strictEqual(store.hasFreshPreferred([github]), false, 'mirror preference must not leak between networks');
store.recordSuccess(r2, { bytes: 500_000, durationMs: 1000 });
assert.strictEqual(store.rank([github, r2])[0], r2);
network = 'network-a';
assert(store.getRecord(github), 'returning to a known network may reuse its still-retained history');

now += RECORD_TTL_MS + 1;
assert.strictEqual(store.getRecord(github), null, 'stale mirror history must be discarded after one day');

const fingerprintA = computeNetworkFingerprint({
    interfaces: { Ethernet: [{ internal: false, family: 'IPv4', address: '10.0.0.2', cidr: '10.0.0.2/24' }] },
    dnsServers: ['1.1.1.1']
});
const fingerprintB = computeNetworkFingerprint({
    interfaces: { Ethernet: [{ internal: false, family: 'IPv4', address: '10.0.1.2', cidr: '10.0.1.2/24' }] },
    dnsServers: ['1.1.1.1']
});
assert.notStrictEqual(fingerprintA, fingerprintB);

fs.writeFileSync(cachePath, '{broken json', 'utf8');
assert.doesNotThrow(() => new MirrorHealthStore({ cachePath }));
fs.rmSync(tempRoot, { recursive: true, force: true });
console.log('mirror-health.test: TTL, cooldown escalation and network-scoped preferences passed');
