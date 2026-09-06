'use strict';

class UpdateOperationMutex {
    constructor() {
        this.active = null;
        this.sequence = 0;
    }

    tryAcquire(initialState = {}) {
        if (this.active) {
            return null;
        }

        const token = Object.freeze({ id: ++this.sequence });
        this.active = {
            token,
            startedAt: Date.now(),
            state: { ...initialState }
        };
        return token;
    }

    update(token, patch = {}) {
        if (!this.isOwner(token)) {
            return false;
        }

        this.active.state = { ...this.active.state, ...patch };
        return true;
    }

    release(token) {
        if (!this.isOwner(token)) {
            return false;
        }

        this.active = null;
        return true;
    }

    isLocked() {
        return this.active !== null;
    }

    isOwner(token) {
        return Boolean(this.active && token && this.active.token === token);
    }

    snapshot() {
        if (!this.active) {
            return null;
        }

        return {
            id: this.active.token.id,
            startedAt: this.active.startedAt,
            ...this.active.state
        };
    }
}

module.exports = { UpdateOperationMutex };
