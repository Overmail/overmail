/** Who is signed in, as `GET /api/users/me` reports them. */
export type CurrentUser = {
    id: string;
    firstname: string;
    lastname: string;
    /** The address of their account, as they wrote it. */
    address: string;
    /**
     * Every address they receive mail under, lowercase -- the account's own and the mail
     * accounts theirs comes in through. What a screen compares the participants of a mail
     * against to find the reader among them; see `isSelf`.
     */
    addresses: string[];
};

const ENDPOINT = "/api/users/me";

/**
 * The signed-in user: their id, what to call them, and their address.
 *
 * Asked once and then kept, and calls that come in while the first one is still on its way share
 * it instead of starting a second request. The server marks the answer `private, max-age=...` on
 * top, so a reload usually does not even reach it.
 *
 * Nothing observes the user yet -- a name changed elsewhere shows up on the next visit, or after
 * [forget].
 */
export class CurrentUserRepository {
    private user: CurrentUser | null = null;
    private inFlight: Promise<CurrentUser | null> | null = null;

    /** Null when nobody is signed in. That is the normal answer, not an error. */
    async get(): Promise<CurrentUser | null> {
        if (this.user) return this.user;
        // Not remembered on failure or while signed out, so the next call tries again.
        this.inFlight ??= this.load().finally(() => (this.inFlight = null));
        return await this.inFlight;
    }

    /** Drops what is known, so the next [get] asks the server again. */
    forget() {
        this.user = null;
    }

    private async load(): Promise<CurrentUser | null> {
        const response = await fetch(ENDPOINT, {credentials: "include"});
        if (response.status === 401) return null;
        if (!response.ok) throw new Error(`Could not read the current user: ${response.status}`);

        const body = await response.json();
        this.user = {
            id: body.id as string,
            firstname: body.firstname as string,
            lastname: body.lastname as string,
            address: body.email as string,
            addresses: body.addresses as string[],
        };
        return this.user;
    }
}
