/** One link that was made for a mail, as `GET /api/emails/{emailId}/shares` reports it. */
export type Share = {
    /** What the link is built from. */
    id: string;
    /** What the owner called this share, to tell their own links apart. */
    shareName: string | null;
    /** Whole seconds since the epoch, like everything dated in the mail api. */
    sharedAt: number;
    /** When the link stops working, or null for one that does not run out. */
    validUntil: number | null;
    includeLabels: boolean;
    /**
     * Whether a visitor is asked for a password. The password itself never leaves the server, so
     * this is all an edit form can show -- see [ShareDraft.password].
     */
    hasPassword: boolean;
    /** Whether subject, sender and date are shown before the password is entered. */
    allowMetadataWithoutPassword: boolean;
};

/**
 * What a screen sends to write a share. The whole share, not a change to it -- with the password
 * as the exception it has to be: the form never had the old one to send back.
 */
export type ShareDraft = {
    shareName: string | null;
    includeLabels: boolean;
    validUntil: number | null;
    /** A password to set. Null on an edit leaves the one that is there, see [removePassword]. */
    password: string | null;
    /** Takes the password off an existing share. Ignored where [password] is set. */
    removePassword?: boolean;
    allowMetadataWithoutPassword: boolean;
};

const endpoint = (emailId: string) => `/api/emails/${encodeURIComponent(emailId)}/shares`;

/**
 * The links one mail was handed out under, as the share dialog reads and edits them.
 *
 * Nothing is cached, like [KnowledgeRepository]: the list is read when the dialog opens and again
 * when something changed it. There are a handful of shares per mail at most, and a stale list here
 * would show a link that was already taken back.
 */
export class ShareRepository {
    async list(emailId: string, signal?: AbortSignal): Promise<Share[]> {
        const response = await fetch(endpoint(emailId), {credentials: "include", signal});
        if (!response.ok) throw new Error(`Could not read the shares: ${response.status}`);

        const body = await response.json();
        return (body.shares as any[]).map(toShare);
    }

    /** Makes a link. The answer carries the id it is built from. */
    async create(emailId: string, draft: ShareDraft, signal?: AbortSignal): Promise<Share> {
        const response = await fetch(endpoint(emailId), {
            method: "POST",
            credentials: "include",
            headers: {"content-type": "application/json"},
            body: JSON.stringify(toBody(draft)),
            signal,
        });

        if (!response.ok) throw new Error(`Could not create the share: ${response.status}`);
        return toShare(await response.json());
    }

    /**
     * Saves an edited share.
     *
     * The id stays, so a link that was handed out keeps working under a new name, a new date or a
     * new password.
     */
    async update(emailId: string, shareId: string, draft: ShareDraft, signal?: AbortSignal): Promise<Share> {
        const response = await fetch(`${endpoint(emailId)}/${encodeURIComponent(shareId)}`, {
            method: "PUT",
            credentials: "include",
            headers: {"content-type": "application/json"},
            body: JSON.stringify(toBody(draft)),
            signal,
        });

        if (!response.ok) throw new Error(`Could not save the share: ${response.status}`);
        return toShare(await response.json());
    }

    /** Takes a link back for good; an expiry only stops it until it is edited. */
    async remove(emailId: string, shareId: string, signal?: AbortSignal): Promise<void> {
        const response = await fetch(`${endpoint(emailId)}/${encodeURIComponent(shareId)}`, {
            method: "DELETE",
            credentials: "include",
            signal,
        });
        if (!response.ok) throw new Error(`Could not delete the share: ${response.status}`);
    }
}

function toShare(share: any): Share {
    return {
        id: share.id as string,
        shareName: (share.share_name ?? null) as string | null,
        sharedAt: (share.shared_at ?? 0) as number,
        validUntil: (share.valid_until ?? null) as number | null,
        includeLabels: (share.include_labels ?? false) as boolean,
        hasPassword: (share.has_password ?? false) as boolean,
        allowMetadataWithoutPassword: (share.allow_metadata_without_password ?? false) as boolean,
    };
}

function toBody(draft: ShareDraft) {
    return {
        share_name: draft.shareName,
        include_labels: draft.includeLabels,
        valid_until: draft.validUntil,
        // Blank is not a password; the server reads it as one that asks for none, and sending it
        // on an edit would read as "set this", which is not what an untouched field means.
        password: draft.password && draft.password.length > 0 ? draft.password : null,
        remove_password: draft.removePassword ?? false,
        allow_metadata_without_password: draft.allowMetadataWithoutPassword,
    };
}

