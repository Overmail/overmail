import {expect, test} from "bun:test";
import {EmailRepository, type EmailMeta} from "./EmailRepository.svelte";
import type {SocketLike} from "./ReconnectingSocket";

class FakeSocket implements SocketLike {
    onopen: (() => void) | null = null;
    onclose: ((event: {wasClean: boolean}) => void) | null = null;
    onmessage: ((event: {data: string}) => void) | null = null;
    closed = false;

    readonly sent: {type: string; ids: string[]}[] = [];

    send(data: string) {
        this.sent.push(JSON.parse(data));
    }

    close() {
        this.closed = true;
        this.onclose?.({wasClean: true});
    }

    drop() {
        this.onclose?.({wasClean: false});
    }

    deliver(emails: unknown[]) {
        this.onmessage?.({data: JSON.stringify({type: "data.emails", emails})});
    }

    deliverUnknown(ids: string[]) {
        this.onmessage?.({data: JSON.stringify({type: "data.emails.unknown", ids})});
    }

    deliverMoved() {
        this.onmessage?.({data: JSON.stringify({type: "update.mails.moved"})});
    }
}

function wireMail(id: string, subject = "Invoice 42") {
    return {
        id,
        subject,
        sent: 1_700_000_000,
        is_read: false,
        preview: "Wie die Mail beginnt",
        archive_state: "unarchive",
        sender: {
            id: "s-1",
            name: "The Sender",
            address: "sender@example.com",
            avatar_url: null,
            avatar_padding: null,
        },
        to: [
            {
                id: "s-2",
                name: null,
                address: "me@example.com",
                avatar_url: null,
                avatar_padding: null,
            },
        ],
        cc: [],
        bcc: [],
        labels: [
            {
                id: "l-1",
                name: "Studium",
                color: "#ffffff",
                description: null,
                assignment_reason: "smells like university",
                created_by_agent: true,
            },
        ],
    };
}

function repository(graceMs = 1) {
    const opened: FakeSocket[] = [];
    const repo = new EmailRepository({
        graceMs,
        reconnectDelays: [1],
        open: () => {
            const socket = new FakeSocket();
            opened.push(socket);
            return socket;
        },
    });
    return {repo, opened, latest: () => opened[opened.length - 1]};
}

/** Lets the queued flush -- and a grace period of 1ms -- run. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 20));

test("a subscription asks the socket and fills the entry", async () => {
    const {repo, latest} = repository();

    repo.subscribe("m-1");
    expect(repo.peek("m-1")).toEqual({value: null, isLoading: true});
    await settle();

    expect(latest().sent).toEqual([{type: "subscribe.emails", ids: ["m-1"]}]);

    latest().deliver([wireMail("m-1")]);
    const mail = repo.peek("m-1").value as EmailMeta;
    expect(mail.subject).toBe("Invoice 42");
    expect(mail.isRead).toBe(false);
    expect(mail.to).toEqual([
        {id: "s-2", name: null, address: "me@example.com", avatarUrl: null, avatarPadding: null},
    ]);
    expect(mail.labels[0]).toEqual({
        id: "l-1",
        name: "Studium",
        color: "#ffffff",
        description: null,
        assignmentReason: "smells like university",
        createdByAgent: true,
    });
});

test("everything a render asks for goes out in one message", async () => {
    const {repo, latest} = repository();

    repo.subscribe("m-1");
    repo.subscribe("m-2");
    repo.subscribe("m-3");
    await settle();

    expect(latest().sent).toEqual([{type: "subscribe.emails", ids: ["m-1", "m-2", "m-3"]}]);
});

test("a mail two screens show is subscribed once and kept while one of them lets go", async () => {
    const {repo, latest} = repository();

    const first = repo.subscribe("m-1");
    const second = repo.subscribe("m-1");
    await settle();
    expect(latest().sent).toEqual([{type: "subscribe.emails", ids: ["m-1"]}]);

    first();
    await settle();
    // Still watched, so nothing was given up.
    expect(latest().sent).toEqual([{type: "subscribe.emails", ids: ["m-1"]}]);

    second();
    await settle();
    expect(latest().sent.at(-1)).toEqual({type: "unsubscribe.emails", ids: ["m-1"]});
});

test("releasing the same handle twice does not drop a mail somebody else watches", async () => {
    const {repo, latest} = repository();

    const release = repo.subscribe("m-1");
    repo.subscribe("m-1");
    await settle();

    release();
    release();
    await settle();

    expect(latest().sent.some((message) => message.type === "unsubscribe.emails")).toBe(false);
});

test("a mail taken back within the grace period is never given up", async () => {
    const {repo, latest} = repository(100);

    repo.subscribe("m-1")();
    // Back before the grace period is over: the server never stopped sending it, so there is
    // nothing to ask for either.
    repo.subscribe("m-1");
    await settle();

    expect(latest().sent).toEqual([{type: "subscribe.emails", ids: ["m-1"]}]);
});

test("an unknown id stops loading", async () => {
    const {repo, latest} = repository();

    repo.subscribe("gone");
    await settle();
    latest().deliverUnknown(["gone"]);

    expect(repo.peek("gone")).toEqual({value: null, isLoading: false});
});

test("an update overwrites what was there", async () => {
    const {repo, latest} = repository();

    repo.subscribe("m-1");
    await settle();
    latest().deliver([wireMail("m-1", "Invoice 42")]);
    latest().deliver([wireMail("m-1", "Invoice 42 (paid)")]);

    expect(repo.peek("m-1").value?.subject).toBe("Invoice 42 (paid)");
});

test("after a reconnect everything on screen is asked for again", async () => {
    const {repo, opened, latest} = repository();

    repo.subscribe("m-1");
    repo.subscribe("m-2");
    await settle();
    latest().onopen?.();

    latest().drop();
    await settle();
    latest().onopen?.();

    expect(opened.length).toBe(2);
    // The server's side of the subscription died with the connection, so the new one is told
    // again -- which is also where the fresh snapshots come from.
    expect(latest().sent).toEqual([{type: "subscribe.emails", ids: ["m-1", "m-2"]}]);
});

test("the socket closes once nothing is on screen", async () => {
    const {repo, latest} = repository();

    const release = repo.subscribe("m-1");
    await settle();
    const connection = latest();

    release();
    await settle();

    expect(connection.closed).toBe(true);
});

test("merge takes what another feed already knows", () => {
    const {repo} = repository();

    repo.merge([
        {
            id: "m-9",
            subject: "From a listing",
            sent: 1,
            isRead: true,
            preview: null,
            archiveState: "unarchive",
            sender: {id: "s", name: null, address: "a@b.c", avatarUrl: null, avatarPadding: null},
            to: [],
            cc: [],
            bcc: [],
            labels: [],
        },
    ]);

    expect(repo.peek("m-9")).toEqual({
        value: expect.objectContaining({subject: "From a listing"}),
        isLoading: false,
    });
});

test("an announcement that the mail moved is counted, not looked up", async () => {
    const {repo, latest} = repository();

    repo.subscribe("m-1");
    await settle();
    expect(repo.revision).toBe(0);

    latest().deliverMoved();
    latest().deliverMoved();

    // Nothing about a mail: what moved is where the mails sit, which is a listing's business.
    expect(repo.revision).toBe(2);
    expect(repo.peek("m-1")).toEqual({value: null, isLoading: true});
});

test("watching for moves keeps the socket up on its own", async () => {
    const {repo, latest, opened} = repository();

    const stop = repo.watchMoves();
    await settle();
    expect(opened.length).toBe(1);
    // No mail is on screen, so there is nothing to ask the server for.
    expect(latest().sent).toEqual([]);

    // A mail that comes and goes does not take the connection with it while this is held.
    repo.subscribe("m-1")();
    await settle();
    expect(latest().closed).toBe(false);

    stop();
    await settle();
    expect(latest().closed).toBe(true);
});

test("a write goes out as it was asked for, and the mail is read again after it", async () => {
    const {repo, latest} = repository();
    const requests: {url: string; method: string | undefined}[] = [];
    const original = globalThis.fetch;
    globalThis.fetch = (async (url: string, init?: {method?: string}) => {
        requests.push({url: String(url), method: init?.method});
        return new Response(null, {status: 204});
    }) as unknown as typeof fetch;

    try {
        repo.subscribe("m-1");
        await settle();

        await repo.setRead("m-1", true);
        expect(requests).toEqual([{url: "/api/emails/m-1/read", method: "POST"}]);

        // And the mail is asked for again: what it looks like now is the server's answer, not a
        // guess made here, and the screen that wrote it must not wait for an announcement it
        // might have missed.
        expect(latest().sent.at(-1)).toEqual({type: "subscribe.emails", ids: ["m-1"]});

        // Every state is the name of the route that puts a mail into it.
        latest().deliver([{...wireMail("m-1"), is_read: true}]);
        await repo.setRead("m-1", false);
        expect(requests.at(-1)).toEqual({url: "/api/emails/m-1/unread", method: "POST"});
        await repo.setArchiveStateTo("m-1", "archive");
        expect(requests.at(-1)).toEqual({url: "/api/emails/m-1/archive", method: "POST"});
        await repo.setArchiveStateTo("m-1", "spam");
        expect(requests.at(-1)).toEqual({url: "/api/emails/m-1/spam", method: "POST"});

        // Asked for where it already is: still a request, because what is known here can be
        // behind and a click that does nothing is worse than one that writes nothing.
        await repo.setArchiveStateTo("m-1", "unarchive");
        expect(requests.at(-1)).toEqual({url: "/api/emails/m-1/unarchive", method: "POST"});

        // A mail nothing is watching is not asked for again -- that would subscribe it.
        const sent = latest().sent.length;
        await repo.setRead("m-2", true);
        expect(requests.at(-1)).toEqual({url: "/api/emails/m-2/read", method: "POST"});
        expect(latest().sent.length).toBe(sent);
    } finally {
        globalThis.fetch = original;
    }
});
