import {expect, mock, test} from "bun:test";
import {InboxSetupRepository, type FolderStreamEvent} from "./InboxSetupRepository";

/** A response whose body arrives in exactly these pieces, so frame splitting is what is tested. */
function streaming(chunks: string[]) {
    const body = new ReadableStream<Uint8Array>({
        start(controller) {
            const encoder = new TextEncoder();
            chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
            controller.close();
        },
    });
    const fetcher = mock(async () => new Response(body, {status: 200}));
    globalThis.fetch = fetcher as unknown as typeof fetch;
    return fetcher;
}

async function collect(repository: InboxSetupRepository): Promise<FolderStreamEvent[]> {
    const events: FolderStreamEvent[] = [];
    for await (const event of repository.streamFolders("h", 993, "u", "p")) events.push(event);
    return events;
}

const FOLDERS_FRAME =
    'data: {"type":"folders","folders":[' +
    '{"path":["INBOX"],"full_name":"INBOX","name":"INBOX","delimiter":".","special_type":"INBOX"},' +
    '{"path":["Archiv","Newsletter"],"full_name":"Archiv.Newsletter","name":"Newsletter","delimiter":".","special_type":null}' +
    "]}\n\n";

test("the tree arrives first, then a count per folder, then done", async () => {
    streaming([
        FOLDERS_FRAME,
        'data: {"type":"stats","full_name":"INBOX","mail_count":535,"oldest_mail_at":"2025-12-27T23:34:15Z"}\n\n',
        'data: {"type":"stats","full_name":"Archiv.Newsletter","mail_count":0,"oldest_mail_at":null}\n\n',
        'data: {"type":"done"}\n\n',
    ]);

    const events = await collect(new InboxSetupRepository());

    expect(events.map((e) => e.type)).toEqual(["folders", "stats", "stats", "done"]);
    expect(events[0]).toEqual({
        type: "folders",
        folders: [
            {path: ["INBOX"], fullName: "INBOX", name: "INBOX", delimiter: ".", specialType: "INBOX"},
            {
                path: ["Archiv", "Newsletter"],
                fullName: "Archiv.Newsletter",
                name: "Newsletter",
                delimiter: ".",
                specialType: null,
            },
        ],
    });
    expect(events[1]).toEqual({
        type: "stats",
        stats: {fullName: "INBOX", mailCount: 535, oldestMailAt: "2025-12-27T23:34:15Z"},
    });
});

test("a frame split across chunks is still one event", async () => {
    // What a real socket does: the boundary falls wherever it falls.
    streaming(['data: {"type":"sta', 'ts","full_name":"INBOX","mail_count":7,', '"oldest_mail_at":null}\n', '\ndata: {"type":"done"}\n\n']);

    const events = await collect(new InboxSetupRepository());

    expect(events).toEqual([
        {type: "stats", stats: {fullName: "INBOX", mailCount: 7, oldestMailAt: null}},
        {type: "done"},
    ]);
});

test("several frames in one chunk are separate events", async () => {
    streaming(['data: {"type":"stats","full_name":"A","mail_count":1,"oldest_mail_at":null}\n\ndata: {"type":"done"}\n\n']);

    expect(await collect(new InboxSetupRepository())).toEqual([
        {type: "stats", stats: {fullName: "A", mailCount: 1, oldestMailAt: null}},
        {type: "done"},
    ]);
});

test("a folder that could not be read keeps its row, with nulls", async () => {
    streaming(['data: {"type":"stats","full_name":"Locked","mail_count":null,"oldest_mail_at":null}\n\n']);

    expect(await collect(new InboxSetupRepository())).toEqual([
        {type: "stats", stats: {fullName: "Locked", mailCount: null, oldestMailAt: null}},
    ]);
});

test("keep-alive comments and unknown events are skipped, not crashed on", async () => {
    streaming([": keep-alive\n\n", 'data: {"type":"something-new"}\n\n', 'data: {"type":"done"}\n\n']);

    expect(await collect(new InboxSetupRepository())).toEqual([{type: "done"}]);
});

test("a mailbox that could not be opened comes through as an error event", async () => {
    streaming(['data: {"type":"error","outcome":"mailbox_unavailable"}\n\n']);

    expect(await collect(new InboxSetupRepository())).toEqual([
        {type: "error", outcome: "mailbox_unavailable"},
    ]);
});

test("the login test reports both outcomes", async () => {
    globalThis.fetch = mock(async () =>
        new Response(JSON.stringify({authenticated: false, outcome: "invalid_credentials"}), {status: 200}),
    ) as unknown as typeof fetch;

    expect(await new InboxSetupRepository().testImapLogin("h", 993, "u", "p")).toEqual({
        authenticated: false,
        outcome: "invalid_credentials",
    });
});

test("the submit body is exactly what the server's route reads", async () => {
    let sent: string | null = null;
    globalThis.fetch = mock(async (_url: string, init: RequestInit) => {
        sent = init.body as string;
        return new Response(JSON.stringify({id: "acc-1"}), {status: 201});
    }) as unknown as typeof fetch;

    const result = await new InboxSetupRepository().submitInbox(
        {host: "imap.example.com", port: 993, username: "julius", password: "secret"},
        [
            {folderName: "INBOX", imapPush: true, aiImport: {type: "only_new_messages"}},
            {folderName: "Archiv/Newsletter", imapPush: false, aiImport: {type: "newest_messages", count: 250}},
            {folderName: "Sent", imapPush: false, aiImport: {type: "after_date", timestamp: 1757023200}},
            {folderName: "Spam", imapPush: false, aiImport: {type: "all_messages"}},
        ],
    );

    expect(result).toEqual({type: "created", id: "acc-1"});
    // Snake case and the `type` discriminator are the contract; SubmitInboxTest.kt pins the
    // other end of it with this same payload.
    expect(JSON.parse(sent!)).toEqual({
        imap: {host: "imap.example.com", port: 993, username: "julius", password: "secret"},
        folder_settings: [
            {folder_name: "INBOX", imap_push: true, ai_import: {type: "only_new_messages"}},
            {folder_name: "Archiv/Newsletter", imap_push: false, ai_import: {type: "newest_messages", count: 250}},
            {folder_name: "Sent", imap_push: false, ai_import: {type: "after_date", timestamp: 1757023200}},
            {folder_name: "Spam", imap_push: false, ai_import: {type: "all_messages"}},
        ],
    });
});

test("a mailbox the user already has is an answer, not a throw", async () => {
    globalThis.fetch = mock(async () => new Response("", {status: 409})) as unknown as typeof fetch;

    expect(
        await new InboxSetupRepository().submitInbox(
            {host: "h", port: 993, username: "u", password: "p"},
            [{folderName: "INBOX", imapPush: false, aiImport: {type: "all_messages"}}],
        ),
    ).toEqual({type: "conflict"});
});
