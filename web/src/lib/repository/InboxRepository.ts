import type {
    InboxConnection,
    SubmitInboxFolder,
    SubmitInboxResult,
    WireAiScope,
} from "$lib/repository/InboxSetupRepository";

/** One connected mailbox, as `GET /api/users/me/inboxes` reports it. */
export type Inbox = {
    id: string;
    host: string;
    port: number;
    /** The imap login, which for most providers is the address itself. */
    username: string;
    /** Whether its importer is switched off. Nothing already imported is affected by that. */
    isPaused: boolean;
    /** The folders being synced, by name, alphabetically. */
    folders: string[];
    /** How many mails were imported through it -- what disconnecting it would take with it. */
    emailCount: number;
};

const ENDPOINT = "/api/users/me/inboxes";

/** One folder's settings, as the edit screen reads them back and sends them again. */
export type InboxFolderSetting = {
    folderName: string;
    imapPush: boolean;
    aiImport: WireAiScope;
};

/** Everything the edit screen opens on. No password: the server never hands one out. */
export type InboxDetail = {
    id: string;
    host: string;
    port: number;
    username: string;
    isPaused: boolean;
    folders: InboxFolderSetting[];
};

/**
 * The mailboxes this user has connected.
 *
 * Nothing is cached: the list is read when a screen shows it and again when something changed it,
 * and both of those are moments where a stale answer would be the wrong one. It is one short
 * query behind one request.
 */
export class InboxRepository {
    async list(signal?: AbortSignal): Promise<Inbox[]> {
        const response = await fetch(ENDPOINT, {credentials: "include", signal});
        if (!response.ok) throw new Error(`Could not read the inboxes: ${response.status}`);

        const body = await response.json();
        return (body.inboxes as any[]).map((inbox) => ({
            id: inbox.id as string,
            host: inbox.host as string,
            port: inbox.port as number,
            username: inbox.username as string,
            isPaused: (inbox.is_paused ?? false) as boolean,
            folders: (inbox.folders ?? []) as string[],
            emailCount: (inbox.email_count ?? 0) as number,
        }));
    }

    /**
     * One mailbox with the settings behind it, which the listing does not carry.
     *
     * The listing is what a table shows and holds folder names only; this is what a form opens on.
     */
    async get(id: string, signal?: AbortSignal): Promise<InboxDetail> {
        const response = await fetch(`${ENDPOINT}/${encodeURIComponent(id)}`, {credentials: "include", signal});
        if (!response.ok) throw new Error(`Could not read the inbox: ${response.status}`);

        const body = await response.json();
        return {
            id: body.id as string,
            host: body.host as string,
            port: body.port as number,
            username: body.username as string,
            isPaused: (body.is_paused ?? false) as boolean,
            folders: ((body.folders ?? []) as any[]).map((folder) => ({
                folderName: folder.folder_name as string,
                imapPush: (folder.imap_push ?? false) as boolean,
                aiImport: folder.ai_import as WireAiScope,
            })),
        };
    }

    /**
     * Saves an edited mailbox.
     *
     * [folders] is the complete set the screen is showing, not a change to it: anything left out
     * is a folder the user took out. An empty password keeps the stored one.
     */
    async update(
        id: string,
        imap: InboxConnection,
        folders: SubmitInboxFolder[],
        signal?: AbortSignal,
    ): Promise<SubmitInboxResult> {
        const response = await fetch(`${ENDPOINT}/${encodeURIComponent(id)}`, {
            method: "PUT",
            credentials: "include",
            headers: {"content-type": "application/json"},
            body: JSON.stringify({
                imap,
                folder_settings: folders.map((folder) => ({
                    folder_name: folder.folderName,
                    imap_push: folder.imapPush,
                    ai_import: folder.aiImport,
                })),
            }),
            signal,
        });

        if (response.status === 409) return {type: "conflict"};
        if (!response.ok) throw new Error(`Could not save the inbox: ${response.status}`);
        return {type: "created", id};
    }

    /**
     * Stops or restarts the import for a mailbox.
     *
     * Nothing is deleted either way -- this only decides whether the importer runs, which is what
     * makes it the gentler answer to "make this stop" than disconnecting.
     */
    async setPaused(id: string, paused: boolean, signal?: AbortSignal): Promise<void> {
        const response = await fetch(`${ENDPOINT}/${encodeURIComponent(id)}/${paused ? "pause" : "resume"}`, {
            method: "POST",
            credentials: "include",
            signal,
        });
        if (!response.ok) throw new Error(`Could not ${paused ? "pause" : "resume"} the inbox: ${response.status}`);
    }

    /**
     * Disconnects a mailbox and answers how many mails went with it.
     *
     * Everything imported through it is deleted server-side; the count is confirmed here rather
     * than assumed, because mail may have arrived between the list being read and this call.
     */
    async remove(id: string, signal?: AbortSignal): Promise<number> {
        const response = await fetch(`${ENDPOINT}/${encodeURIComponent(id)}`, {
            method: "DELETE",
            credentials: "include",
            signal,
        });
        if (!response.ok) throw new Error(`Could not delete the inbox: ${response.status}`);

        const body = await response.json();
        return (body.deleted_emails ?? 0) as number;
    }
}
