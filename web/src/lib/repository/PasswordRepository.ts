const ENDPOINT = "/api/users/me/password";

/** What the server refused, by the field it named; anything else is "failed". */
export type PasswordFailure = "current_password" | "new_password" | "failed";

export class PasswordError extends Error {
    constructor(readonly failure: PasswordFailure) {
        super(`The password request failed: ${failure}`);
    }
}

/**
 * The password the sign-in asks for instead of a mailed code. The hash never comes back, only
 * whether there is one.
 */
export class PasswordRepository {
    async isSet(signal?: AbortSignal): Promise<boolean> {
        const response = await fetch(ENDPOINT, {credentials: "include", signal});
        if (!response.ok) throw new PasswordError("failed");
        return (await response.json()).is_set as boolean;
    }

    /** [currentPassword] is required once a password is set, and ignored before. */
    async set(newPassword: string, currentPassword: string | null): Promise<void> {
        await this.send("PUT", {new_password: newPassword, current_password: currentPassword});
    }

    async remove(currentPassword: string): Promise<void> {
        await this.send("DELETE", {current_password: currentPassword});
    }

    private async send(method: "PUT" | "DELETE", body: unknown): Promise<void> {
        const response = await fetch(ENDPOINT, {
            method,
            credentials: "include",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify(body),
        });
        if (response.ok) return;

        // A 400 names the field it is about, which is what the dialog marks.
        const parameter = response.status === 400
            ? (await response.json().catch(() => null))?.error?.details?.parameter
            : null;
        throw new PasswordError(
            parameter === "current_password" || parameter === "new_password" ? parameter : "failed",
        );
    }
}
