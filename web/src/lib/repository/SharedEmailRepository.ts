/** A label on a shared mail. Name and colour only: a visitor has nothing to sort by them. */
export type SharedLabel = {
    name: string;
    color: string;
};

/** Who wrote the shared mail, when, and about what. */
export type SharedEmailMetadata = {
    subject: string;
    /** Display name from the mail's header, absent for a bare address. */
    senderName: string | null;
    senderAddress: string;
    /** Whole seconds since the epoch, like everything dated in the mail api. */
    sent: number;
    /** Only where the share was made with them; empty otherwise. */
    labels: SharedLabel[];
};

/** The mail itself, as both halves it was imported with. */
export type SharedEmailContent = {
    text: string | null;
    html: string | null;
};

/** A shared mail as much as the link hands out right now. */
export type SharedEmail = {
    /** Whether the mail is behind a password the visitor has not typed yet. */
    needsPassword: boolean;
    /** Null where the share keeps even the subject behind its password. */
    metadata: SharedEmailMetadata | null;
    /** Null until the share is open. */
    content: SharedEmailContent | null;
};

/** The link ran out. It was real, so this is not the same as an id nobody ever had. */
export class ShareExpiredError extends Error {
    constructor() {
        super("This share has run out");
    }
}

/** No share under that id -- an unknown link, or one that was taken back. */
export class ShareNotFoundError extends Error {
    constructor() {
        super("No such share");
    }
}

/** The password does not open this share. */
export class WrongSharePasswordError extends Error {
    constructor() {
        super("That is not the password of this share");
    }
}

const endpoint = (shareId: string) => `/api/shares/${encodeURIComponent(shareId)}`;

/**
 * A shared mail, as the page behind a share link reads it.
 *
 * Nothing is cached and nothing is sent along: the request carries no session, because holding
 * the link is the whole authorization. The password is not kept here either -- the page holds
 * what was typed for as long as it is open, and hands it to [open].
 */
export class SharedEmailRepository {
    /** What the link shows without a password. Throws for a link that ran out or never was. */
    async read(shareId: string, signal?: AbortSignal): Promise<SharedEmail> {
        return toShared(await this.request(fetch(endpoint(shareId), {signal})));
    }

    /** The same with the password typed in. Throws [WrongSharePasswordError] for a wrong one. */
    async open(shareId: string, password: string, signal?: AbortSignal): Promise<SharedEmail> {
        const response = fetch(`${endpoint(shareId)}/open`, {
            method: "POST",
            headers: {"content-type": "application/json"},
            body: JSON.stringify({password}),
            signal,
        });

        return toShared(await this.request(response));
    }

    /** The one place the api's failures become the three a share page can be in. */
    private async request(pending: Promise<Response>): Promise<any> {
        const response = await pending;

        if (response.status === 410) throw new ShareExpiredError();
        if (response.status === 404) throw new ShareNotFoundError();
        if (response.status === 403) throw new WrongSharePasswordError();
        if (!response.ok) throw new Error(`Could not read the share: ${response.status}`);

        return await response.json();
    }
}

function toShared(shared: any): SharedEmail {
    const metadata = shared.metadata;
    const content = shared.content;

    return {
        needsPassword: (shared.needs_password ?? false) as boolean,
        metadata: metadata
            ? {
                  subject: (metadata.subject ?? "") as string,
                  senderName: (metadata.sender_name ?? null) as string | null,
                  senderAddress: (metadata.sender_address ?? "") as string,
                  sent: (metadata.sent ?? 0) as number,
                  labels: ((metadata.labels ?? []) as any[]).map((label) => ({
                      name: label.name as string,
                      color: label.color as string,
                  })),
              }
            : null,
        content: content
            ? {text: (content.text ?? null) as string | null, html: (content.html ?? null) as string | null}
            : null,
    };
}
