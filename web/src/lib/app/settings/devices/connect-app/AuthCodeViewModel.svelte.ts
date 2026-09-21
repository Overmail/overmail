import type {AuthCodeState} from "$lib/repository/DevicesSettingsRepository";

/** How long a failed fetch waits before it tries again on its own. */
export const RETRY_DELAY_MS = 5000;

/**
 * The sign-in code the app scans, kept current: a new one is fetched the moment the shown one runs
 * out, so the QR code on screen always works. A failed fetch is retried every [RETRY_DELAY_MS]
 * until one succeeds -- or right away, when the user asks.
 */
export class AuthCodeViewModel {
    state: AuthCodeState = $state({type: "loading"});

    private nextFetch: ReturnType<typeof setTimeout> | null = null;
    private disposed = false;

    constructor(
        private readonly fetchAuthCode: () => Promise<AuthCodeState>,
        private readonly retryDelayMs: number = RETRY_DELAY_MS,
    ) {}

    /** Fetches a code now, and schedules the next fetch from how that went. */
    async renew(): Promise<void> {
        this.clearNextFetch();
        this.state = {type: "loading"};

        const state = await this.fetchAuthCode();
        // Answered after the section was closed: nobody is looking, and nothing may be scheduled.
        if (this.disposed) return;
        this.state = state;

        if (state.type === "error") {
            this.scheduleFetch(this.retryDelayMs);
        } else if (state.type === "ready") {
            this.scheduleFetch(Math.max(0, state.validUntil.getTime() - Date.now()));
        }
    }

    /** Stops renewing; the section is gone and nobody is looking at the code anymore. */
    dispose(): void {
        this.disposed = true;
        this.clearNextFetch();
    }

    private scheduleFetch(delay: number): void {
        this.clearNextFetch();
        this.nextFetch = setTimeout(() => void this.renew(), delay);
    }

    private clearNextFetch(): void {
        if (this.nextFetch) clearTimeout(this.nextFetch);
        this.nextFetch = null;
    }
}
