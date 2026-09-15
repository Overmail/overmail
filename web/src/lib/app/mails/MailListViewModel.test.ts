import {expect, test} from "bun:test";
import {MailListViewModel, everyMail} from "./MailListViewModel.svelte";
import type {EmailRepository} from "$lib/repository/EmailRepository.svelte";
import type {ViewSettings} from "$lib/app/views/viewSettings";

/** What was asked of the api, as the part of the url that says what for. */
let requests: string[] = [];

/** A view of the mailbox: the groups it is cut into, and the mails of each of them in order. */
function mailbox(groups: Record<string, string[]>, levels: ViewSettings["groupings"] = [{kind: "date_smart", reversed: false}]) {
    requests = [];

    globalThis.fetch = (async (url: string) => {
        const target = new URL(url, "http://localhost");
        const group = target.searchParams.get("group");
        requests.push(`${target.pathname}|${group ?? ""}|${target.searchParams.get("offset") ?? ""}`);

        if (target.pathname.endsWith("/groups")) {
            const counted = Object.entries(groups).map(([keys, ids]) => ({
                keys: keys === "" ? [] : keys.split(","),
                count: ids.length,
            }));

            return new Response(JSON.stringify({groupings: [], groups: counted}), {status: 200});
        }

        // Every mail under the group asked for: fewer keys than levels is the level above, which
        // is what a header of an outer level asks for.
        const under = Object.entries(groups)
            .filter(([keys]) => group === null || keys === group || keys.startsWith(group + ","))
            .flatMap(([, ids]) => ids);

        if (target.pathname.endsWith("/ids")) {
            return new Response(JSON.stringify({total: under.length, ids: under}), {status: 200});
        }

        const offset = Number(target.searchParams.get("offset") ?? 0);
        const limit = Number(target.searchParams.get("limit") ?? 100);

        return new Response(
            JSON.stringify({total: under.length, ids: under.slice(offset, offset + limit)}),
            {status: 200}
        );
    }) as unknown as typeof fetch;

    return {levels};
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

test("the shape and the first page arrive, and the rows are the two together", async () => {
    const {levels} = mailbox({"1": ["a", "b"], "2": ["c"]});
    const list = new MailListViewModel(repository().repository);
    list.setView(view(levels));

    list.window(0, 20);
    await settle();

    // Two stretches: a header, two mails, a header, one mail.
    expect(list.layout.length).toBe(5);
    expect(list.total).toBe(3);
    expect(list.idAt({path: ["1"], offset: 0})).toBe("a");
    expect(list.idAt({path: ["2"], offset: 0})).toBe("c");
    expect(list.initialized).toBe(true);
});

test("a group is asked for once, however many of its rows are on screen", async () => {
    const {levels} = mailbox({"1": ["a", "b", "c"]});
    const list = new MailListViewModel(repository().repository);
    list.setView(view(levels));

    list.window(0, 20);
    await settle();
    list.window(0, 20);
    await settle();

    const pages = requests.filter((request) => request.startsWith("/api/emails/list|"));
    expect(pages).toEqual(["/api/emails/list|1|0"]);
});

test("a window that reaches into a second group asks for that one as well", async () => {
    const {levels} = mailbox({"1": ["a"], "2": ["b"], "3": ["c"]});
    const list = new MailListViewModel(repository().repository);
    list.setView(view(levels));

    // The first two stretches only: a header and a mail each.
    list.window(0, 3);
    await settle();

    const pages = requests.filter((request) => request.startsWith("/api/emails/list|"));
    expect(pages).toEqual(["/api/emails/list|1|0", "/api/emails/list|2|0"]);
});

test("a second level puts a header under the first", async () => {
    const levels: ViewSettings["groupings"] = [
        {kind: "date_smart", reversed: false},
        {kind: "sender", reversed: false},
    ];
    mailbox({"1,alice": ["a"], "1,bob": ["b", "c"]}, levels);
    const list = new MailListViewModel(repository().repository);
    list.setView(view(levels));

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
    mailbox({"1,alice": ["a"], "1,bob": ["b"]}, levels);
    const list = new MailListViewModel(repository().repository);
    list.setView(view(levels));

    list.window(0, 20);
    await settle();

    const day = list.layout.roots[0];
    expect(await list.idsOfGroup(day)).toEqual(["a", "b"]);
    await list.idsOfGroup(day);

    // The outer header asks for its own path, and the answer is held for the second click.
    const stretches = requests.filter((request) => request.startsWith("/api/emails/list/ids|"));
    expect(stretches).toEqual(["/api/emails/list/ids|1|"]);
});

test("changing the view keeps what the old one read", async () => {
    const levels: ViewSettings["groupings"] = [{kind: "date_smart", reversed: false}];
    mailbox({"1": ["a", "b"]}, levels);
    const list = new MailListViewModel(repository().repository);
    list.setView(view(levels));

    list.window(0, 20);
    await settle();
    expect(list.total).toBe(2);

    // Another filter is another listing; the fake answers the same mails, which is enough to see
    // that it was read again rather than shown from what the first one held.
    list.setView(view(levels, {...everyMail(), readState: false}));
    await settle();
    expect(list.total).toBe(2);

    const shapes = requests.filter((request) => request.startsWith("/api/emails/list/groups"));
    expect(shapes.length).toBe(2);
});

test("stepping walks the mails, headers and groups alike", async () => {
    const {levels} = mailbox({"1": ["a", "b"], "2": ["c"]});
    const list = new MailListViewModel(repository().repository);
    list.setView(view(levels));

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
    const {levels} = mailbox({"1": ["a", "b"], "2": ["c"]});
    const {held, repository: mails} = repository();
    const list = new MailListViewModel(mails);
    list.setView(view(levels));

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

test("a move reads the shape and the pages again", async () => {
    const {levels} = mailbox({"1": ["a"]});
    const list = new MailListViewModel(repository().repository);
    list.setView(view(levels));

    list.window(0, 20);
    await settle();
    const before = requests.length;

    list.refresh();
    await settle();

    expect(requests.length).toBeGreaterThan(before);
});
