/** Who a session was issued to, as the server recorded it. Every field may be "unknown". */
export type SessionClient =
    | {type: "web"; browser: string; device: string; os: string}
    | {type: "android"; device: string; manufacturer: string; os: string}
    | {type: "ios"; device: string; os: string};

/** One place this user is signed in, as `GET /api/users/me/sessions` reports it. */
export type UserSession = {
    id: string;
    client: SessionClient;
    issuedAt: Date;
    /** Whether this is the session the browser asking is signed in with. */
    isCurrentSession: boolean;
};

const ENDPOINT = "/api/users/me/sessions";

/**
 * Where this user is signed in. Nothing is cached: the list changes whenever a device signs in or
 * is signed out, so the screen that shows it asks again on its own.
 */
export class SessionsRepository {
    async list(signal?: AbortSignal): Promise<UserSession[]> {
        const response = await fetch(ENDPOINT, {credentials: "include", signal});
        if (!response.ok) throw new Error(`Could not read the sessions: ${response.status}`);

        const body = await response.json();
        return (body.sessions as any[]).map((session) => ({
            id: session.id,
            client: session.client,
            issuedAt: new Date(session.issued_at),
            isCurrentSession: session.is_current_session,
        }));
    }

    /** Signs the session out; its token is refused from the next request on. */
    async revoke(id: string, signal?: AbortSignal): Promise<void> {
        const response = await fetch(`${ENDPOINT}/${encodeURIComponent(id)}`, {
            method: "DELETE",
            credentials: "include",
            signal,
        });
        if (!response.ok) throw new Error(`Could not revoke the session: ${response.status}`);
    }
}
