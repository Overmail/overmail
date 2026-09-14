import {expect, mock, test} from "bun:test";
import {ViewRepository} from "./ViewRepository.svelte";
import {ViewSocket, type View, type ViewFilter} from "./ViewSocket";

/** A socket that sends nothing; these tests are about the writes, not about the list arriving. */
const silentSocket = () =>
    new ViewSocket({
        onViews: () => {},
        open: () => ({
            send: () => {},
            close: () => {},
            onopen: null,
            onclose: null,
            onmessage: null,
        }),
    });

/** A filter that restricts nothing, which is what a view has until somebody sets one. */
const NO_FILTER = {
    readState: null,
    archivedState: null,
    imapAccountIds: null,
    sentBy: null,
    sentTo: null,
    hasLabels: null,
} satisfies ViewFilter;

function repository(views: {id: string; name: string}[]): ViewRepository {
    const repo = new ViewRepository(silentSocket());
    repo.views = views.map((view, index) => ({
        id: view.id,
        name: view.name,
        sortKey: `a${index}`,
        groupings: [],
        filter: {...NO_FILTER},
        sorting: {kind: "date", reversed: false},
    })) satisfies View[];

    return repo;
}

function answering(status: number, body: unknown = null) {
    const fetcher = mock(async () => new Response(body === null ? "" : JSON.stringify(body), {status}));
    globalThis.fetch = fetcher as unknown as typeof fetch;
    return fetcher;
}

const PAYLOAD = {
    id: "1",
    name: "Erste",
    sort_key: "a1",
    view: {
        groupings: [{type: "sender", sort_reversed: true}],
        email_sorting: {type: "subject", sort_reversed: false},
    },
};

test("a rename sends only the name", async () => {
    const fetcher = answering(200, PAYLOAD);

    const view = await repository([{id: "1", name: "First"}]).update("1", {name: "Erste"});

    expect((fetcher as any).mock.calls[0][0]).toBe("/api/users/me/views/1");
    const request = (fetcher as any).mock.calls[0][1];
    expect(request.method).toBe("PATCH");
    expect(JSON.parse(request.body)).toEqual({name: "Erste"});
    // The answer is the view as the socket would send it.
    expect(view.groupings).toEqual([{kind: "sender", reversed: true}]);
});

test("the row carries the new name before the server has answered", async () => {
    let release: (() => void) | null = null;
    globalThis.fetch = mock(
        () =>
            new Promise((resolve) => {
                release = () => resolve(new Response(JSON.stringify(PAYLOAD), {status: 200}));
            })
    ) as unknown as typeof fetch;

    const repo = repository([{id: "1", name: "First"}, {id: "2", name: "Second"}]);
    const renaming = repo.update("1", {name: "Erste"});

    // The old name showing until the socket answers reads as a rename that did not take.
    expect(repo.views.map((view) => view.name)).toEqual(["Erste", "Second"]);

    release!();
    await renaming;
});

test("a rename the server refuses puts the old name back", async () => {
    answering(500);

    const repo = repository([{id: "1", name: "First"}]);

    await expect(repo.update("1", {name: "Erste"})).rejects.toThrow();

    expect(repo.views.map((view) => view.name)).toEqual(["First"]);
});

test("the settings are not applied before the answer", async () => {
    answering(200, PAYLOAD);

    const repo = repository([{id: "1", name: "First"}]);
    await repo.update("1", {
        settings: {groupings: [], filter: {...NO_FILTER}, sorting: {kind: "date", reversed: true}},
    });

    // Only the name is worth applying early; the rest is not on screen while it is sent, and
    // the socket is what says what the view now is.
    expect(repo.views[0].sorting).toEqual({kind: "date", reversed: false});
});

test("a move is sent as the view it goes behind", async () => {
    const fetcher = answering(200, PAYLOAD);

    await repository([{id: "1", name: "First"}, {id: "2", name: "Second"}]).move("1", "2");

    expect(JSON.parse((fetcher as any).mock.calls[0][1].body)).toEqual({
        position: {after_view_id: "2"},
    });
});

test("a move to the top keeps the null inside the position", async () => {
    const fetcher = answering(200, PAYLOAD);

    await repository([{id: "1", name: "First"}, {id: "2", name: "Second"}]).move("2", null);

    // Present with a null, not absent: absent would mean no move was asked for at all.
    expect(JSON.parse((fetcher as any).mock.calls[0][1].body)).toEqual({
        position: {after_view_id: null},
    });
});

test("the list reads in the new order before the server has answered", async () => {
    let release: (() => void) | null = null;
    globalThis.fetch = mock(
        () =>
            new Promise((resolve) => {
                release = () => resolve(new Response(JSON.stringify(PAYLOAD), {status: 200}));
            })
    ) as unknown as typeof fetch;

    const repo = repository([{id: "1", name: "First"}, {id: "2", name: "Second"}]);
    const moving = repo.move("1", "2");

    expect(repo.views.map((view) => view.id)).toEqual(["2", "1"]);

    release!();
    await moving;
    expect(repo.views.map((view) => view.id)).toEqual(["2", "1"]);
});

test("a move the server refuses puts the list back", async () => {
    answering(500);

    const repo = repository([{id: "1", name: "First"}, {id: "2", name: "Second"}]);

    await expect(repo.move("1", "2")).rejects.toThrow();

    expect(repo.views.map((view) => view.id)).toEqual(["1", "2"]);
});

test("the settings go over as the whole object", async () => {
    const fetcher = answering(200, PAYLOAD);

    await repository([{id: "1", name: "First"}]).update("1", {
        settings: {
            groupings: [{kind: "sender", reversed: true}],
            filter: {...NO_FILTER, readState: false, hasLabels: ["l-1"]},
            sorting: {kind: "subject", reversed: false},
        },
    });

    expect(JSON.parse((fetcher as any).mock.calls[0][1].body)).toEqual({
        view: {
            groupings: [{type: "sender", sort_reversed: true}],
            // Nulls and all: the server replaces the settings with this, so a key that is not
            // there would clear what the view filters by.
            filter: {
                read_state: false,
                archived_state: null,
                imap_account_ids: null,
                sent_by: null,
                sent_to: null,
                has_labels: ["l-1"],
            },
            email_sorting: {type: "subject", sort_reversed: false},
        },
    });
});

test("a delete asks the view's own endpoint", async () => {
    const fetcher = answering(204);

    const repo = repository([{id: "1", name: "First"}, {id: "2", name: "Second"}]);
    await repo.remove("1");

    expect((fetcher as any).mock.calls[0][0]).toBe("/api/users/me/views/1");
    expect((fetcher as any).mock.calls[0][1].method).toBe("DELETE");
    // The row leaves the list when the socket sends it without it, not here.
    expect(repo.views.map((view) => view.id)).toEqual(["1", "2"]);
});

test("a delete the server refuses throws", async () => {
    answering(500);

    await expect(repository([{id: "1", name: "First"}]).remove("1")).rejects.toThrow();
});
