import {expect, test} from "bun:test";
import {MailListViewModel, everyMail} from "./MailListViewModel.svelte";
import type {EmailRepository} from "$lib/repository/EmailRepository.svelte";
import type {SocketLike} from "$lib/repository/ReconnectingSocket";
import type {ViewSettings} from "$lib/app/views/viewSettings";

/** What was asked of the `/ids` endpoint, which is the one thing still fetched. */
let requests: string[] = [];

/**
 * The server's side of the listing socket: it holds a mailbox, answers what is watched, and
 * answers it again when [move] says the mailbox changed.
 */
class FakeListingServer implements SocketLike {
    onopen: (() => void) | null = null;
    onclose: ((event: {wasClean: boolean}) => void) | null = null;
    onmessage: ((event: {data: string}) => void) | null = null;

    /** Everything the client sent, parsed. */
    readonly sent: Record<string, unknown>[] = [];

    /** How often the shape and a page went out, so a test can see that it was answered again. */
    answers = 0;

    private token = 0;
    private pages: {group: string; offset: number; limit: number}[] = [];

    constructor(private groups: Record<string, string[]>) {}

    send(data: string) {
        const message = JSON.parse(data) as Record<string, unknown>;
        this.sent.push(message);

        if (message.type === "watch.listing") {
            this.token = message.token as number;
            this.pages = [];
            this.sendGroups();
        }

        if (message.type === "watch.pages") {
            this.pages = message.pages as typeof this.pages;
            this.pages.forEach((page) => this.sendPage(page));
        }
    }

    close() {
        this.onclose?.({wasClean: true});
    }

    /** The mailbox moved: the shape and every watched page go out again, unasked. */
    move(groups: Record<string, string[]>) {
        this.groups = groups;
        this.sendGroups();
        this.pages.forEach((page) => this.sendPage(page));
    }

    /** The mails under a group key, which may name a level above the deepest one. */
    private under(group: string): string[] {
        return Object.entries(this.groups)
            .filter(([keys]) => group === "" || keys === group || keys.startsWith(group + ","))
            .flatMap(([, ids]) => ids);
    }

    private sendGroups() {
        this.answers++;
        const groups = Object.entries(this.groups).map(([keys, ids]) => ({
            keys: keys === "" ? [] : keys.split(","),
            count: ids.length,
        }));

        this.emit({type: "data.listing.groups", token: this.token, groupings: [], groups});
    }

    private sendPage(page: {group: string; offset: number; limit: number}) {
        this.answers++;
        const under = this.under(page.group);

        this.emit({
            type: "data.listing.page",
            token: this.token,
            group: page.group,
            offset: page.offset,
            total: under.length,
            ids: under.slice(page.offset, page.offset + page.limit),
        });
    }

    private emit(message: unknown) {
        this.onmessage?.({data: JSON.stringify(message)});
    }
}

/** A view of the mailbox: the groups it is cut into, and the mails of each of them in order. */
function mailbox(
    groups: Record<string, string[]>,
    levels: ViewSettings["groupings"] = [{kind: "date_smart", reversed: false}]
) {
    requests = [];
    const servers: FakeListingServer[] = [];

    // The stretch endpoint is the one request left, see MailListViewModel.idsOfGroup.
    globalThis.fetch = (async (url: string) => {
        const target = new URL(url, "http://localhost");
        const group = target.searchParams.get("group");
        requests.push(`${target.pathname}|${group ?? ""}`);

        const under = Object.entries(groups)
            .filter(([keys]) => group === null || keys === group || keys.startsWith(group + ","))
            .flatMap(([, ids]) => ids);

        return new Response(JSON.stringify({total: under.length, ids: under}), {status: 200});
    }) as unknown as typeof fetch;

    return {
        levels,
        servers,
        latest: () => servers[servers.length - 1],
        open: () => {
            const server = new FakeListingServer(groups);
            servers.push(server);
            // The socket only says what it watches once it is open, like a real one.
            queueMicrotask(() => server.onopen?.());
            return server;
        },
    };
}

/** A repository that only counts what is subscribed; what a mail *is* is not this test's. */
function repository() {
    const held = new Map<string, number>();

    const stub = {
        subscribe(id: string) {
            held.set(id, (held.get(id) ?? 0) + 1);
            return () => held.set(id, (held.get(id) ?? 1) - 1);
        },
        peek: () => ({value: null, isLoading: false}),
    };

    return {held, repository: stub as unknown as EmailRepository};
}

const view = (groupings: ViewSettings["groupings"], filter = everyMail()): ViewSettings => ({
    groupings,
    filter,
    sorting: {kind: "date", reversed: false},
});

const settle = () => new Promise((resolve) => setTimeout(resolve, 10));

/** A list on the mailbox above, already watching. */
function listing(box: ReturnType<typeof mailbox>, mails = repository().repository) {
    const list = new MailListViewModel(mails, {open: box.open, reconnectDelays: [1]});
    list.setView(view(box.levels));
    return list;
}

test("the shape and the first page arrive, and the rows are the two together", async () => {
    const box = mailbox({"1": ["a", "b"], "2": ["c"]});
    const list = listing(box);

    await settle();
    list.window(0, 20);
    await settle();

    // Two stretches: a header, two mails, a header, one mail.
    expect(list.layout.length).toBe(5);
    expect(list.total).toBe(3);
    expect(list.idAt({path: ["1"], offset: 0})).toBe("a");
    expect(list.idAt({path: ["2"], offset: 0})).toBe("c");
    expect(list.initialized).toBe(true);
});

test("a group is watched once, however many of its rows are on screen", async () => {
    const box = mailbox({"1": ["a", "b", "c"]});
    const list = listing(box);

    await settle();
    list.window(0, 20);
    await settle();
    list.window(0, 20);
    await settle();

    const watched = box.latest().sent.filter((message) => message.type === "watch.pages");
    expect(watched).toEqual([{type: "watch.pages", pages: [{group: "1", offset: 0, limit: 100}]}]);
});

test("a window that reaches into a second group watches that one as well", async () => {
    const box = mailbox({"1": ["a"], "2": ["b"], "3": ["c"]});
    const list = listing(box);

    await settle();
    // The first two stretches only: a header and a mail each.
    list.window(0, 3);
    await settle();

    const watched = box.latest().sent.filter((message) => message.type === "watch.pages").at(-1);
    expect(watched).toEqual({
        type: "watch.pages",
        pages: [
            {group: "1", offset: 0, limit: 100},
            {group: "2", offset: 0, limit: 100},
        ],
    });
});

test("a second level puts a header under the first", async () => {
    const levels: ViewSettings["groupings"] = [
        {kind: "date_smart", reversed: false},
        {kind: "sender", reversed: false},
    ];
    const box = mailbox({"1,alice": ["a"], "1,bob": ["b", "c"]}, levels);
    const list = listing(box);

    await settle();
    list.window(0, 20);
    await settle();

    // The day's header, then bob (more mail, so first) with two, then alice with one.
    expect(list.layout.length).toBe(6);
    expect(list.layout.rowAt(0)).toMatchObject({kind: "header"});
    expect(list.idAt({path: ["1", "bob"], offset: 0})).toBe("b");
    expect(list.idAt({path: ["1", "alice"], offset: 0})).toBe("a");
});

test("ticking a header asks for every mail under it, and only once", async () => {
    const levels: ViewSettings["groupings"] = [
        {kind: "date_smart", reversed: false},
        {kind: "sender", reversed: false},
    ];
    const box = mailbox({"1,alice": ["a"], "1,bob": ["b"]}, levels);
    const list = listing(box);

    await settle();
    list.window(0, 20);
    await settle();

    const day = list.layout.roots[0];
    expect(await list.idsOfGroup(day)).toEqual(["a", "b"]);
    await list.idsOfGroup(day);

    // The outer header asks for its own path, and the answer is held for the second click.
    expect(requests).toEqual(["/api/emails/list/ids|1"]);
});

test("changing the view watches the other listing, and keeps what this one read", async () => {
    const levels: ViewSettings["groupings"] = [{kind: "date_smart", reversed: false}];
    const box = mailbox({"1": ["a", "b"]}, levels);
    const list = listing(box);

    await settle();
    list.window(0, 20);
    await settle();
    expect(list.total).toBe(2);

    // Another filter is another listing; the fake answers the same mails, which is enough to see
    // that it was watched again rather than shown from what the first one held.
    list.setView(view(levels, {...everyMail(), readState: false}));
    await settle();
    expect(list.total).toBe(2);

    const watches = box.latest().sent.filter((message) => message.type === "watch.listing");
    expect(watches.length).toBe(2);
    // Each watch is its own, so an answer to the first is not filed under the second.
    expect(watches[0].token).not.toBe(watches[1].token);
});

test("an answer to a watch that has been left behind is dropped", async () => {
    const levels: ViewSettings["groupings"] = [{kind: "date_smart", reversed: false}];
    const box = mailbox({"1": ["a", "b"]}, levels);
    const list = listing(box);

    await settle();
    list.window(0, 20);
    await settle();

    list.setView(view(levels, {...everyMail(), readState: false}));
    await settle();

    // The old watch answering late, with three mails it never had: nothing on screen moves.
    box.latest().onmessage?.({
        data: JSON.stringify({
            type: "data.listing.groups",
            token: 1,
            groupings: [],
            groups: [{keys: ["1"], count: 3}],
        }),
    });

    expect(list.total).toBe(2);
});

test("stepping walks the mails, headers and groups alike", async () => {
    const box = mailbox({"1": ["a", "b"], "2": ["c"]});
    const list = listing(box);

    await settle();
    list.window(0, 20);
    await settle();

    expect(list.step("a", 1)).toBe("b");
    // Over the header of the next stretch, which is not a mail.
    expect(list.step("b", 1)).toBe("c");
    expect(list.step("c", 1)).toBeUndefined();
    expect(list.canStep("c", -1)).toBe(true);
    expect(list.canStep("a", -1)).toBe(false);
});

test("what is on screen is subscribed, and what left it is let go", async () => {
    const box = mailbox({"1": ["a", "b"], "2": ["c"]});
    const {held, repository: mails} = repository();
    const list = listing(box, mails);

    await settle();
    list.window(0, 20);
    await settle();
    expect(held.get("a")).toBe(1);

    // Only the second stretch is in the window now.
    list.window(3, 4);
    await settle();
    expect(held.get("a")).toBe(0);
    expect(held.get("c")).toBe(1);

    list.dispose();
    expect([...held.values()].every((count) => count === 0)).toBe(true);
});

test("a mail arriving is on screen without anybody having asked", async () => {
    const box = mailbox({"1": ["a"]});
    const list = listing(box);

    await settle();
    list.window(0, 20);
    await settle();
    expect(list.total).toBe(1);

    const asked = box.latest().sent.length;

    // The server's side of a move: it sends the shape and the watched pages again, unasked.
    box.latest().move({"1": ["new", "a"]});
    await settle();

    expect(list.total).toBe(2);
    expect(list.idAt({path: ["1"], offset: 0})).toBe("new");
    // Nothing went out for it -- that is the whole point of pushing the index.
    expect(box.latest().sent.length).toBe(asked);
});

test("a reconnect says again what is on screen", async () => {
    const box = mailbox({"1": ["a", "b"]});
    const list = listing(box);

    await settle();
    list.window(0, 20);
    await settle();

    const first = box.latest();
    first.onclose?.({wasClean: false});
    await settle();

    const second = box.latest();
    expect(second).not.toBe(first);
    expect(second.sent.map((message) => message.type)).toEqual(["watch.listing", "watch.pages"]);
    expect(list.total).toBe(2);
});
