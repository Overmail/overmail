import {useAuthentiktContext, type PluginLike} from "@julius-babies/authentikt-svelte";

type AuthentiktClient = ReturnType<typeof useAuthentiktContext>;

export type EmailVerificationStatus = "ready" | "loading" | "invalid_code" | "error";

/**
 * The client half of the server's `EmailVerificationPlugin`: the code it mailed to the account,
 * typed back in.
 */
export class EmailVerificationPlugin implements PluginLike {
    code = $state("");
    status = $state<EmailVerificationStatus>("ready");

    private readonly auth: AuthentiktClient;
    private readonly _ns: string;

    constructor(auth: AuthentiktClient, namespace: string) {
        this.auth = auth;
        this._ns = namespace;
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
        this.status = "loading";
        try {
            const url = new URL("steps/plugins/" + this._ns + "/verify", this.auth.sessionUrl);
            const response = await fetch(url, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({code: this.code}),
            });
            const data = await response.json();

            if (data.type === "success") {
                await this.auth.updateState();
                this.status = "ready";
            } else {
                this.status = "invalid_code";
            }
        } catch (e) {
            console.error(e);
            this.status = "error";
        }
    };
}
