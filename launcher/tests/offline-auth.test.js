'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const {
    OFFLINE_DEVELOPER_ARGUMENT,
    createOfflineAuthorization,
    uuidV3Dns
} = require('../offline-auth');

assert.strictEqual(
    uuidV3Dns('NeDelKA'),
    'd272f02d-01ee-35e2-b9d1-d3cef6b93e3a',
    'the repaired launcher must preserve existing Eclipse offline UUIDs'
);
assert.notStrictEqual(uuidV3Dns('NeDelKA'), uuidV3Dns('OtherPlayer'),
    'changing username must never reuse the previous process-global UUID');

const authorization = createOfflineAuthorization('  NeDelKA  ');
assert.strictEqual(authorization.name, 'NeDelKA');
assert.strictEqual(authorization.meta.type, 'legacy',
    'offline Eclipse sessions must not pretend to be Mojang/MSA sessions');
assert.strictEqual(authorization.access_token, authorization.uuid);
assert.throws(() => createOfflineAuthorization('   '), /никнейм/);
assert.strictEqual(OFFLINE_DEVELOPER_ARGUMENT, '--offlineDeveloperMode');

const mainSource = fs.readFileSync(path.join(__dirname, '..', 'main.js'), 'utf8');
assert(mainSource.includes('customLaunchArgs: [OFFLINE_DEVELOPER_ARGUMENT]'),
    'Minecraft must receive the official offline mode flag so UserApiService stays offline');
assert(!mainSource.includes('Authenticator.getAuth('),
    'launcher must not manufacture a UUID and send it to Mojang as an access token');

console.log('offline-auth.test: deterministic identity and offline User API invariants passed');
