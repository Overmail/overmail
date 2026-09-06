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
                email_count: 2649,
                is_paused: true,
            },
        ],
    });

    expect(await new InboxRepository().list()).toEqual([
        {
            id: "acc-1",
            host: "imap.example.com",
            port: 993,
            username: "julius@example.com",
            isPaused: true,
            folders: ["Archiv/Newsletter", "INBOX"],
            emailCount: 2649,
        },
    ]);
    expect((fetcher as any).mock.calls[0][0]).toBe("/api/users/me/inboxes");
});

test("a mailbox without folders or mail is still a mailbox", async () => {
    answering(200, {inboxes: [{id: "a", host: "h", port: 993, username: "u"}]});

    const inbox = (await new InboxRepository().list())[0];
    expect(inbox.folders).toEqual([]);
    expect(inbox.emailCount).toBe(0);
    // A mailbox the server says nothing about is running, not paused.
    expect(inbox.isPaused).toBe(false);
});

test("deleting answers with how many mails went with the mailbox", async () => {
    const fetcher = mock(async () => new Response(JSON.stringify({deleted_emails: 2649}), {status: 200}));
    globalThis.fetch = fetcher as unknown as typeof fetch;

    expect(await new InboxRepository().remove("acc-1")).toBe(2649);
    expect((fetcher as any).mock.calls[0][0]).toBe("/api/users/me/inboxes/acc-1");
    expect((fetcher as any).mock.calls[0][1].method).toBe("DELETE");
});

test("a delete that did not happen throws, so the row is not removed from the table", async () => {
    answering(500);

    expect(new InboxRepository().remove("acc-1")).rejects.toThrow();
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
