import {expect, test} from "bun:test";
import {ViewSocket, type View, type ViewFilterPayload} from "./ViewSocket";
import type {SocketLike} from "./ReconnectingSocket";

/** A socket whose events this test fires by hand. */
class FakeSocket implements SocketLike {
    onopen: (() => void) | null = null;
    onclose: ((event: {wasClean: boolean}) => void) | null = null;
    onmessage: ((event: {data: string}) => void) | null = null;
    closed = false;

    send() {
        // Nothing is sent over this socket; the connection is the whole subscription.
    }

    close() {
        this.closed = true;
        this.onclose?.({wasClean: true});
    }

    /** The server dropping the connection. */
    drop() {
        this.onclose?.({wasClean: false});
    }

    sendViews(...views: {id: string; name: string; sortKey?: string; filter?: ViewFilterPayload}[]) {
        this.onmessage?.({
            data: JSON.stringify({
                type: "data.views",
                views: views.map((view) => ({
                    id: view.id,
                    name: view.name,
                    sort_key: view.sortKey ?? "a0",
                    view: {
                        groupings: [{type: "sender", sort_reversed: true}],
                        // Left out unless a test asks for one, which is what a view stored
                        // before filters existed looks like.
                        ...(view.filter === undefined ? {} : {filter: view.filter}),
                        email_sorting: {type: "subject", sort_reversed: false},
                    },
                })),
            }),
        });
    }
}

function socket() {
    const opened: FakeSocket[] = [];
    const lists: View[][] = [];
    const views = new ViewSocket({
        onViews: (received) => lists.push(received),
        open: () => {
            const fake = new FakeSocket();
            opened.push(fake);
            return fake;
        },
        // No waiting in a test; the delays themselves are a constant, not behaviour.
        reconnectDelays: [1],
    });
    return {views, opened, lists, latest: () => opened[opened.length - 1]};
}

/** Long enough for a reconnect scheduled with a 1ms delay. */
const afterReconnect = () => new Promise((resolve) => setTimeout(resolve, 20));

test("passes on the list the server sends, in the order it sent it", () => {
    const {views, latest, lists} = socket();

    views.start();
    latest().onopen?.();
    latest().sendViews({id: "1", name: "First", sortKey: "a0"}, {id: "2", name: "Second", sortKey: "a1"});

    expect(lists.length).toBe(1);
    expect(lists[0].map((view) => view.name)).toEqual(["First", "Second"]);
});

test("reads the settings of a view", () => {
    const {views, latest, lists} = socket();

    views.start();
    latest().onopen?.();
    latest().sendViews({id: "1", name: "First"});

    expect(lists[0][0]).toEqual({
        id: "1",
        name: "First",
        sortKey: "a0",
        groupings: [{kind: "sender", reversed: true}],
        // No filter in the payload is a filter that restricts nothing -- what a view stored
        // before filters existed says.
        filter: {
            readState: null,
            archivedState: null,
            imapAccountIds: null,
            sentBy: null,
            sentTo: null,
            hasLabels: null,
        },
        sorting: {kind: "subject", reversed: false},
    });
});

test("reads the filter of a view", () => {
    const {views, latest, lists} = socket();

    views.start();
    latest().onopen?.();
    latest().sendViews({
        id: "1",
        name: "First",
        filter: {read_state: false, archived_state: ["Archive", "Spam"], has_labels: ["l-1"]},
    });

    expect(lists[0][0].filter).toEqual({
        readState: false,
        archivedState: ["Archive", "Spam"],
        hasLabels: ["l-1"],
        // What the payload does not name restricts nothing.
        imapAccountIds: null,
        sentBy: null,
        sentTo: null,
    });
});

test("an emptied list is passed on as well", () => {
    const {views, latest, lists} = socket();

    views.start();
    latest().onopen?.();
    latest().sendViews({id: "1", name: "First"});
    latest().sendViews();

    expect(lists[1]).toEqual([]);
});

test("connects once, no matter how often it is started", () => {
    const {views, opened} = socket();

    views.start();
    views.start();

    expect(opened.length).toBe(1);
});

test("comes back after the connection drops, and the new list is the one that counts", async () => {
    const {views, opened, latest, lists} = socket();

    views.start();
    latest().onopen?.();
    latest().sendViews({id: "1", name: "First"});
    latest().drop();
    await afterReconnect();

    expect(opened.length).toBe(2);

    // Nothing has to be re-requested: a fresh connection starts by sending the list.
    latest().onopen?.();
    latest().sendViews({id: "1", name: "Renamed"});
    expect(lists[lists.length - 1].map((view) => view.name)).toEqual(["Renamed"]);
});

test("stop closes it and does not reconnect", async () => {
    const {views, opened, latest} = socket();

    views.start();
    latest().onopen?.();
    const connection = latest();
    views.stop();
    await afterReconnect();

    expect(connection.closed).toBe(true);
    expect(opened.length).toBe(1);
});
