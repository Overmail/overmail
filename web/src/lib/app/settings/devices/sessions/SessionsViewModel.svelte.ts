import {SvelteSet} from "svelte/reactivity";
import type {UserSession} from "$lib/repository/SessionsRepository";

/** How often the list is read again; a device that signs in elsewhere shows up within this. */
export const POLL_INTERVAL_MS = 5000;

/**
 * The sessions of this user, read again every [POLL_INTERVAL_MS], and signing one of them out.
 *
 * The next read is scheduled when the last one is answered, not on a fixed clock, so a slow server
 * never has two reads in flight. A failed read keeps the list that was there -- it is the best
 * there is -- and the next poll tries again.
 */
export class SessionsViewModel {
    /** Null until the first read was answered. */
    sessions: UserSession[] | null = $state(null);
    /** Whether the last read or revocation failed. */
    failed = $state(false);
    /** The sessions being signed out right now, so their button cannot be pressed twice. */
    readonly revoking = new SvelteSet<string>();

    private nextPoll: ReturnType<typeof setTimeout> | null = null;
    private abort = new AbortController();

    /**
     * [signedOut] is what happens once the session of this very browser was revoked: every
     * request from here on is refused, so the page has nothing left to show.
     */
    constructor(
        private readonly list: (signal: AbortSignal) => Promise<UserSession[]>,
        private readonly revokeSession: (id: string, signal: AbortSignal) => Promise<void>,
        private readonly signedOut: () => void,
        private readonly pollIntervalMs: number = POLL_INTERVAL_MS,
    ) {}

    /** Reads the list now, and keeps reading it until [dispose]. */
    async poll(): Promise<void> {
        try {
            this.sessions = await this.list(this.abort.signal);
            this.failed = false;
        } catch {
            if (this.abort.signal.aborted) return;
            this.failed = true;
        }
        if (this.abort.signal.aborted) return;
        this.nextPoll = setTimeout(() => void this.poll(), this.pollIntervalMs);
    }

    /** Signs [session] out and drops it from the list, without waiting for the next poll. */
    async revoke(session: UserSession): Promise<void> {
        if (this.revoking.has(session.id)) return;
        this.revoking.add(session.id);
        try {
            await this.revokeSession(session.id, this.abort.signal);
        } catch {
            if (!this.abort.signal.aborted) this.failed = true;
            return;
        } finally {
            this.revoking.delete(session.id);
        }

        if (session.isCurrentSession) {
            this.signedOut();
            return;
        }
        this.sessions = this.sessions?.filter((it) => it.id !== session.id) ?? null;
    }

    dispose(): void {
        this.abort.abort();
        if (this.nextPoll) clearTimeout(this.nextPoll);
        this.nextPoll = null;
    }
}
