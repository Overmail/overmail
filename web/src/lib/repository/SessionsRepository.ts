/** Who a session was issued to, as the server recorded it. Every field may be "unknown". */
export type SessionClient =
    | {type: "web"; browser: string; device: string; os: string}
    | {type: "android"; device: string; manufacturer: string; os: string}
    | {type: "ios"; device: string; os: string};

/**
 * Whether a push reaches the session: `unavailable` when its client cannot take one at all (a
 * browser), `available` when it could but has not registered for one, `setup` when it has.
 */
export type PushNotificationState = "unavailable" | "available" | "setup";

/** One place this user is signed in, as `GET /api/users/me/sessions` reports it. */
export type UserSession = {
    id: string;
    client: SessionClient;
    issuedAt: Date;
    /** Whether this is the session the browser asking is signed in with. */
    isCurrentSession: boolean;
    pushNotificationState: PushNotificationState;
};

const ENDPOINT = "/api/users/me/sessions";

/** The server has no credentials for the device's platform (Firebase, APNs), so no push goes out to it. */
export class PushUnavailableError extends Error {
    constructor() {
        super("Push notifications are not configured on this server");
    }
}

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
            pushNotificationState: session.push_notification_state,
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

    /**
     * Has the session's device show a test notification. Queued: the answer does not say it
     * arrived. Throws [PushUnavailableError] when the server cannot push to that device.
     */
    async sendTestPush(id: string, signal?: AbortSignal): Promise<void> {
        const response = await fetch(`${ENDPOINT}/${encodeURIComponent(id)}/test-push`, {
            method: "POST",
            credentials: "include",
            signal,
        });
        if (response.status === 503) throw new PushUnavailableError();
        if (!response.ok) throw new Error(`Could not send a test push: ${response.status}`);
    }
}
