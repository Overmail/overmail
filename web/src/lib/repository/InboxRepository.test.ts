import {expect, mock, test} from "bun:test";
import {InboxRepository} from "./InboxRepository";

function answering(status: number, body: unknown = null) {
    const fetcher = mock(async () => new Response(body === null ? "" : JSON.stringify(body), {status}));
    globalThis.fetch = fetcher as unknown as typeof fetch;
    return fetcher;
}

test("reads the mailboxes and their folders", async () => {
    const fetcher = answering(200, {
        inboxes: [
            {
                id: "acc-1",
                host: "imap.example.com",
                port: 993,
                username: "julius@example.com",
                folders: ["Archiv/Newsletter", "INBOX"],
            },
        ],
    });

    expect(await new InboxRepository().list()).toEqual([
        {
            id: "acc-1",
            host: "imap.example.com",
            port: 993,
            username: "julius@example.com",
            folders: ["Archiv/Newsletter", "INBOX"],
        },
    ]);
    expect((fetcher as any).mock.calls[0][0]).toBe("/api/users/me/inboxes");
});

test("a mailbox without folders is still a mailbox", async () => {
    answering(200, {inboxes: [{id: "a", host: "h", port: 993, username: "u"}]});

    expect((await new InboxRepository().list())[0].folders).toEqual([]);
});

test("nothing connected is an empty list", async () => {
    answering(200, {inboxes: []});

    expect(await new InboxRepository().list()).toEqual([]);
});

test("a failed read throws rather than showing an empty account list", async () => {
    answering(500);

    // An empty table would read as "you have no mailboxes", which is a different thing.
    expect(new InboxRepository().list()).rejects.toThrow();
});
