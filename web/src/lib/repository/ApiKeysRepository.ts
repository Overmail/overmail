/** One api key, as `GET /api/users/me/api-keys` lists it. The key itself is never in here. */
export type ApiKey = {
    id: string;
    name: string;
    /** The start of the key: enough to match it to the one in a script, not enough to use it. */
    prefix: string;
    createdAt: Date;
    /** To the minute; null for a key that was never used. */
    lastUsedAt: Date | null;
};

/** A key that was just made, with the key itself -- the one time the server hands it out. */
export type CreatedApiKey = {
    apiKey: ApiKey;
    key: string;
};

const ENDPOINT = "/api/users/me/api-keys";

/** Where the server's api documentation is, which is what a key is for. */
export const API_DOCUMENTATION_URL = "/api/swagger";

/**
 * The api keys of this user. Nothing is cached, like [SessionsRepository]: the list is read when
 * the screen shows it and again when something changed it.
 */
export class ApiKeysRepository {
    async list(signal?: AbortSignal): Promise<ApiKey[]> {
        const response = await fetch(ENDPOINT, {credentials: "include", signal});
        if (!response.ok) throw new Error(`Could not read the api keys: ${response.status}`);

        const body = await response.json();
        return (body.api_keys as any[]).map(toApiKey);
    }

    async create(name: string, signal?: AbortSignal): Promise<CreatedApiKey> {
        const response = await fetch(ENDPOINT, {
            method: "POST",
            credentials: "include",
            headers: {"content-type": "application/json"},
            body: JSON.stringify({name}),
            signal,
        });
        if (!response.ok) throw new Error(`Could not create the api key: ${response.status}`);

        const body = await response.json();
        return {apiKey: toApiKey(body.api_key), key: body.key as string};
    }

    /** Deletes a key; it is refused from the next request on. */
    async remove(id: string, signal?: AbortSignal): Promise<void> {
        const response = await fetch(`${ENDPOINT}/${encodeURIComponent(id)}`, {
            method: "DELETE",
            credentials: "include",
            signal,
        });
        if (!response.ok) throw new Error(`Could not delete the api key: ${response.status}`);
    }
}

function toApiKey(key: any): ApiKey {
    return {
        id: key.id as string,
        name: key.name as string,
        prefix: key.prefix as string,
        // Whole seconds since the epoch, like every timestamp of this api.
        createdAt: new Date((key.created_at as number) * 1000),
        lastUsedAt: key.last_used_at != null ? new Date((key.last_used_at as number) * 1000) : null,
    };
}
