'use strict';

const assert = require('assert');
const { UpdateOperationMutex } = require('../update-operation-mutex');

const mutex = new UpdateOperationMutex();
assert.strictEqual(mutex.isLocked(), false, 'mutex must start unlocked');

const first = mutex.tryAcquire({ progress: 10, message: 'first update' });
assert(first, 'first update must acquire the mutex');
assert.strictEqual(mutex.isLocked(), true, 'mutex must be locked after acquire');
assert.strictEqual(mutex.tryAcquire({ progress: 0 }), null, 'parallel update must be rejected');
assert.deepStrictEqual(
    { progress: mutex.snapshot().progress, message: mutex.snapshot().message },
    { progress: 10, message: 'first update' },
    'active update state must be available to duplicate callers'
);

const forgedToken = { id: first.id };
assert.strictEqual(mutex.update(forgedToken, { progress: 50 }), false, 'forged token must not mutate the owner state');
assert.strictEqual(mutex.release(forgedToken), false, 'forged token must not release the mutex');
assert.strictEqual(mutex.update(first, { progress: 75 }), true, 'owner must update its state');
assert.strictEqual(mutex.snapshot().progress, 75, 'updated progress must be retained');
assert.strictEqual(mutex.release(first), true, 'owner must release the mutex');
assert.strictEqual(mutex.isLocked(), false, 'mutex must unlock after owner release');

const second = mutex.tryAcquire({ progress: 0 });
assert(second && second.id > first.id, 'a later update must receive a new token');
assert.strictEqual(mutex.release(first), false, 'stale owner must not release a newer operation');
assert.strictEqual(mutex.isOwner(second), true, 'new operation must retain ownership');
assert.strictEqual(mutex.release(second), true, 'new owner must release normally');

console.log('update-operation-mutex.test: exclusive ownership and stale-token safety passed');
