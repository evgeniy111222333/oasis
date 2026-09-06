'use strict';

const assert = require('assert');
const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');
const { downloadFileAdaptive, MirrorHealthStore, resumablePartialPath } = require('../update-network');

function startServer(handler) {
    const sockets = new Set();
    const server = http.createServer(handler);
    server.on('connection', socket => {
        sockets.add(socket);
        socket.once('close', () => sockets.delete(socket));
    });
    return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve({
        url: `http://127.0.0.1:${server.address().port}/artifact.bin`,
        close: () => new Promise(done => {
            for (const socket of sockets) socket.destroy();
            server.close(done);
        })
    })));
}

function serveRangePayload(payload, request, response, onRange) {
    const match = String(request.headers.range || '').match(/^bytes=(\d+)-(\d*)$/);
    const start = match ? Number(match[1]) : 0;
    if (onRange) onRange(start, request.headers.range || '');
    if (start >= payload.length) {
        response.writeHead(416, { 'Content-Range': `bytes */${payload.length}` });
        response.end();
        return;
    }
    const body = payload.subarray(start);
    response.writeHead(match ? 206 : 200, {
        'Content-Type': 'application/octet-stream',
        'Accept-Ranges': 'bytes',
        'Content-Length': body.length,
        ...(match ? { 'Content-Range': `bytes ${start}-${payload.length - 1}/${payload.length}` } : {})
    });
    response.end(body);
}

(async () => {
    const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'eclipse-adaptive-download-'));
    const payload = Buffer.alloc(640 * 1024);
    for (let index = 0; index < payload.length; index++) payload[index] = index % 251;
    const descriptor = {
        size: payload.length,
        sha1: crypto.createHash('sha1').update(payload).digest('hex'),
        sha256: crypto.createHash('sha256').update(payload).digest('hex')
    };
    const servers = [];
    try {
        const blocked = await startServer((_request, response) => {
            response.writeHead(200, { 'Content-Length': payload.length });
            response.write(payload.subarray(0, 8 * 1024));
        });
        const healthy = await startServer((request, response) => serveRangePayload(payload, request, response));
        servers.push(blocked, healthy);

        const selectionHealth = new MirrorHealthStore({
            cachePath: path.join(tempRoot, 'selection-health.json'),
            fingerprintProvider: () => 'selection-network'
        });
        const selectedDest = path.join(tempRoot, 'selected.bin');
        const selected = await downloadFileAdaptive({
            urls: [blocked.url, healthy.url],
            dest: selectedDest,
            descriptor,
            timeoutMs: 250,
            probeTimeoutMs: 120,
            probeBytes: 64 * 1024,
            rounds: 1,
            healthStore: selectionHealth
        });
        assert.strictEqual(selected, healthy.url, 'the first mirror to deliver real content should win');
        assert.deepStrictEqual(fs.readFileSync(selectedDest), payload);
        assert.strictEqual(selectionHealth.hasFreshPreferred([healthy.url]), true);

        let flakyFullRequests = 0;
        const flaky = await startServer((request, response) => {
            const isProbe = Boolean(request.headers.range);
            if (isProbe) {
                serveRangePayload(payload, request, response);
                return;
            }
            flakyFullRequests++;
            response.writeHead(200, { 'Content-Length': payload.length, 'Accept-Ranges': 'bytes' });
            response.write(payload.subarray(0, 160 * 1024));
            setTimeout(() => response.destroy(), 10);
        });
        let resumedAt = 0;
        const resumeMirror = await startServer((request, response) => serveRangePayload(payload, request, response, start => {
            if (start > 0) resumedAt = start;
        }));
        servers.push(flaky, resumeMirror);

        const resumeHealth = new MirrorHealthStore({
            cachePath: path.join(tempRoot, 'resume-health.json'),
            fingerprintProvider: () => 'resume-network'
        });
        resumeHealth.recordSuccess(flaky.url, { bytes: payload.length, durationMs: 100 });
        const resumedDest = path.join(tempRoot, 'resumed.bin');
        const resumedSource = await downloadFileAdaptive({
            urls: [flaky.url, resumeMirror.url],
            dest: resumedDest,
            descriptor,
            timeoutMs: 300,
            probeTimeoutMs: 120,
            probeBytes: 64 * 1024,
            rounds: 1,
            healthStore: resumeHealth
        });
        assert.strictEqual(flakyFullRequests, 1);
        assert.strictEqual(resumedSource, resumeMirror.url);
        assert(resumedAt > 0, 'the second mirror must continue from the preserved partial offset');
        assert.deepStrictEqual(fs.readFileSync(resumedDest), payload);
        assert.strictEqual(fs.readdirSync(tempRoot).some(name => name.includes('.resume-') && name.endsWith('.part')), false);

        let noRangeRequests = 0;
        const noRange = await startServer((_request, response) => {
            noRangeRequests++;
            response.writeHead(200, { 'Content-Length': payload.length });
            response.end(payload);
        });
        servers.push(noRange);
        const noRangeDest = path.join(tempRoot, 'no-range.bin');
        const noRangePartial = resumablePartialPath(noRangeDest, descriptor, [noRange.url]);
        fs.writeFileSync(noRangePartial, payload.subarray(0, 96 * 1024));
        await downloadFileAdaptive({
            urls: [noRange.url],
            dest: noRangeDest,
            descriptor,
            timeoutMs: 300,
            rounds: 1
        });
        assert.strictEqual(noRangeRequests, 2, 'a mirror that ignores Range must be retried once from byte zero');
        assert.deepStrictEqual(fs.readFileSync(noRangeDest), payload);

        const oldWorking = Buffer.from('last known good file');
        const protectedDest = path.join(tempRoot, 'protected.bin');
        fs.writeFileSync(protectedDest, oldWorking);
        const badPayload = Buffer.alloc(payload.length, 7);
        const corrupt = await startServer((request, response) => serveRangePayload(badPayload, request, response));
        servers.push(corrupt);
        await assert.rejects(downloadFileAdaptive({
            urls: [corrupt.url],
            dest: protectedDest,
            descriptor,
            timeoutMs: 300,
            probeTimeoutMs: 120,
            rounds: 1
        }), /mismatch/i);
        assert.deepStrictEqual(fs.readFileSync(protectedDest), oldWorking, 'an invalid update must not replace the working file');

        console.log('adaptive-download.test: content race, Range resume/no-Range restart, integrity and rollback safety passed');
    } finally {
        await Promise.all(servers.map(server => server.close()));
        fs.rmSync(tempRoot, { recursive: true, force: true });
    }
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
