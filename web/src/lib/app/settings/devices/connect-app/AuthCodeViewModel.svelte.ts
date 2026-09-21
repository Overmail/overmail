import type {AuthCodeState} from "$lib/repository/DevicesSettingsRepository";

/**
 * The sign-in code the app scans, kept current: a new one is fetched the moment the shown one runs
 * out, so the QR code on screen always works.
 */
export class AuthCodeViewModel {
    state: AuthCodeState = $state({type: "loading"});

    private renewTimeout: ReturnType<typeof setTimeout> | null = null;

    constructor(private readonly fetchAuthCode: () => Promise<AuthCodeState>) {}

    /** Fetches a code, and the next one when it runs out. */
    async renew(): Promise<void> {
        this.state = {type: "loading"};
        const state = await this.fetchAuthCode();
        this.state = state;
        if (state.type !== "ready") return;

        this.clearTimeout();
        const timeLeft = state.validUntil.getTime() - Date.now();
        if (timeLeft > 0) this.renewTimeout = setTimeout(() => void this.renew(), timeLeft);
    }

    /** Stops renewing; the section is gone and nobody is looking at the code anymore. */
    dispose(): void {
        this.clearTimeout();
    }

    private clearTimeout(): void {
        if (this.renewTimeout) clearTimeout(this.renewTimeout);
        this.renewTimeout = null;
    }
}
