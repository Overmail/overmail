import {createSubscriber} from "svelte/reactivity";
import {
    useAuthentiktContext,
    type RateLimitPayload,
    type RateLimitState,
} from "@julius-babies/authentikt-svelte";

type AuthentiktClient = ReturnType<typeof useAuthentiktContext>;

/**
 * The `rate_limit` of a step's payload as a ticking {@link RateLimitState}, for a step of our own.
 * authentikt's built-in steps expose the same thing as `plugin.rateLimit`, but do not export the
 * tracker behind it, so this reads the payload the same way.
 */
export class RateLimitTracker {
    private readonly auth: AuthentiktClient;
    private readonly namespace: string;
    private refreshedLock: number | null = null;

    // Ticks only while somebody reads a locked state, and reloads the flow once the lock is over,
    // which is what brings the tries back.
    private readonly subscribe = createSubscriber((update) => {
        const interval = setInterval(() => {
            const lockedUntil = this.lockedUntil;
            if (lockedUntil !== null && Date.now() >= lockedUntil && this.refreshedLock !== lockedUntil) {
                this.refreshedLock = lockedUntil;
                void this.auth.updateState();
            }
            update();
        }, 1000);
        return () => clearInterval(interval);
    });

    constructor(auth: AuthentiktClient, namespace: string) {
        this.auth = auth;
        this.namespace = namespace;
    }

    private get payload(): RateLimitPayload | null {
        const step = this.auth.currentFlow?.step;
        if (step?.type !== "step" || step.namespace !== this.namespace) return null;
        return (step.payload?.rate_limit as RateLimitPayload | undefined) ?? null;
    }

    /** Relative to when the step was loaded, so the local clock does not have to match the server's. */
    private get lockedUntil(): number | null {
        const retryAfter = this.payload?.retry_after_seconds;
        if (retryAfter == null) return null;
        return this.auth.stepReceivedAt + retryAfter * 1000;
    }

    get state(): RateLimitState | null {
        const payload = this.payload;
        if (!payload) return null;

        const lockedUntil = this.lockedUntil;
        if (lockedUntil !== null) this.subscribe();
        const remainingMs = lockedUntil === null ? 0 : Math.max(0, lockedUntil - Date.now());

        return {
            maxTries: payload.max_tries,
            periodSeconds: payload.period_seconds,
            remainingTries: payload.remaining_tries,
            isLocked: remainingMs > 0,
            remainingLockSeconds: Math.ceil(remainingMs / 1000),
            lockedUntil: remainingMs > 0 ? new Date(lockedUntil!) : null,
        };
    }
}
