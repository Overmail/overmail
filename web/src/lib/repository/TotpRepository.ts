const ENDPOINT = "/api/users/me/totp";

export type TotpStatus = {
    isEnabled: boolean;
    /** Whether the sign-in offers a mailed code instead of the app's. */
    emailFallback: boolean;
};

/** A secret to scan; nothing is stored until a code from it is confirmed. */
export type TotpSetup = {
    secret: string;
    /** otpauth URI, for the QR code. */
    uri: string;
};

/** What the server refused: the code, by name; anything else is "failed". */
export type TotpFailure = "code" | "conflict" | "failed";

export class TotpError extends Error {
    constructor(readonly failure: TotpFailure) {
        super(`The authenticator request failed: ${failure}`);
    }
}

/** The authenticator app the sign-in asks for a code from. The secret is only seen while setting up. */
export class TotpRepository {
    async status(signal?: AbortSignal): Promise<TotpStatus> {
        const response = await fetch(ENDPOINT, {credentials: "include", signal});
        if (!response.ok) throw new TotpError("failed");
        const body = await response.json();
        return {isEnabled: body.is_enabled, emailFallback: body.email_fallback};
    }

    async setup(): Promise<TotpSetup> {
        const response = await fetch(`${ENDPOINT}/setup`, {method: "POST", credentials: "include"});
        if (!response.ok) throw new TotpError("failed");
        return await response.json();
    }

    async enable(secret: string, code: string): Promise<void> {
        await this.send("PUT", ENDPOINT, {secret, code});
    }

    async disable(code: string): Promise<void> {
        await this.send("DELETE", ENDPOINT, {code});
    }

    async setEmailFallback(enabled: boolean): Promise<void> {
        await this.send("PUT", `${ENDPOINT}/email-fallback`, {enabled});
    }

    private async send(method: "PUT" | "DELETE", url: string, body: unknown): Promise<void> {
        const response = await fetch(url, {
            method,
            credentials: "include",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify(body),
        });
        if (response.ok) return;
        if (response.status === 409) throw new TotpError("conflict");

        const parameter = response.status === 400
            ? (await response.json().catch(() => null))?.error?.details?.parameter
            : null;
        throw new TotpError(parameter === "code" ? "code" : "failed");
    }
}
