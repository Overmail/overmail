import {useAuthentiktContext, type PluginLike, type RateLimitState} from "@julius-babies/authentikt-svelte";
import {RateLimitTracker} from "./rateLimit.svelte";

type AuthentiktClient = ReturnType<typeof useAuthentiktContext>;

export type EmailVerificationStatus = "ready" | "loading" | "invalid_code" | "rate_limited" | "error";

/**
 * The client half of the server's `EmailVerificationPlugin`: the code it mailed to the account,
 * typed back in.
 */
export class EmailVerificationPlugin implements PluginLike {
    code = $state("");
    status = $state<EmailVerificationStatus>("ready");

    private readonly auth: AuthentiktClient;
    private readonly _ns: string;
    private readonly rateLimitTracker: RateLimitTracker;

    constructor(auth: AuthentiktClient, namespace: string) {
        this.auth = auth;
        this._ns = namespace;
        this.rateLimitTracker = new RateLimitTracker(auth, namespace);
    }

    /** Wrong codes left, and the lock once they are used up; like authentikt's own steps. */
    get rateLimit(): RateLimitState | null {
        return this.rateLimitTracker.state;
    }

    get namespace(): string {
        return this._ns;
    }

    get isActive(): boolean {
        const step = this.auth.currentFlow?.step;
        return step?.type === "step" && step.namespace === this._ns;
    }

    /** Where the code went, masked by the server. */
    get maskedEmail(): string | undefined {
        const step = this.auth.currentFlow?.step;
        if (step?.type !== "step") return undefined;
        return step.payload?.email as string | undefined;
    }

    submit = async (): Promise<void> => {
        if (this.rateLimit?.isLocked) return;
        this.status = "loading";
        try {
            const url = new URL("steps/plugins/" + this._ns + "/verify", this.auth.sessionUrl);
            const response = await fetch(url, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({code: this.code}),
            });
            if (response.status === 409) {
                // No longer this step, e.g. a duplicate submission; show the one that is.
                await this.auth.updateState();
                this.status = "ready";
                return;
            }
            if (response.status === 429) {
                // The lock itself comes with the step's state.
                await this.auth.updateState();
                this.status = "rate_limited";
                return;
            }
            const data = await response.json();

            if (data.type === "success") {
                await this.auth.updateState();
                this.status = "ready";
            } else {
                // For the tries that are left.
                await this.auth.updateState();
                this.status = "invalid_code";
            }
        } catch (e) {
            console.error(e);
            this.status = "error";
        }
    };
}
