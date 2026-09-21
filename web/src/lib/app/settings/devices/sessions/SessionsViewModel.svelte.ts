import type {UserSession} from "$lib/repository/SessionsRepository";

/** How often the list is read again; a device that signs in elsewhere shows up within this. */
export const POLL_INTERVAL_MS = 5000;

/**
 * The sessions of this user, read again every [POLL_INTERVAL_MS].
 *
 * The next read is scheduled when the last one is answered, not on a fixed clock, so a slow server
 * never has two reads in flight. A failed read keeps the list that was there -- it is the best
 * there is -- and the next poll tries again.
 */
export class SessionsViewModel {
    /** Null until the first read was answered. */
    sessions: UserSession[] | null = $state(null);
    /** Whether the last read failed. */
    failed = $state(false);

    private nextPoll: ReturnType<typeof setTimeout> | null = null;
    private abort = new AbortController();

    constructor(
        private readonly list: (signal: AbortSignal) => Promise<UserSession[]>,
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

    dispose(): void {
        this.abort.abort();
        if (this.nextPoll) clearTimeout(this.nextPoll);
        this.nextPoll = null;
    }
}
