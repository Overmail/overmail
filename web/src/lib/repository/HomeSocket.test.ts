import {expect, test} from "bun:test";
import {HomeSocket, type MailGraph, type SocketLike} from "./HomeSocket";

/** A socket whose events this test fires by hand. */
class FakeSocket implements SocketLike {
    onopen: (() => void) | null = null;
    onclose: ((event: {wasClean: boolean}) => void) | null = null;
    onmessage: ((event: {data: string}) => void) | null = null;
    closed = false;

    /** Everything the client sent over this connection, parsed. */
    readonly sent: Record<string, unknown>[] = [];

    send(data: string) {
        this.sent.push(JSON.parse(data));
    }

    close() {
        this.closed = true;
        this.onclose?.({wasClean: true});
    }

    /** The server dropping the connection. */
    drop() {
        this.onclose?.({wasClean: false});
    }

    sendCount(unarchived: number) {
        this.onmessage?.({data: JSON.stringify({type: "data.mailbox.count", unarchived})});
    }

    sendGraph(year: number, days: Record<string, number>, availableYears = [year]) {
        this.onmessage?.({
            data: JSON.stringify({
                type: "data.mail_graph",
                year,
                available_years: availableYears,
                days,
            }),
        });
    }
}

function socket() {
    const opened: FakeSocket[] = [];
    const counts: number[] = [];
    const graphs: MailGraph[] = [];
    const home = new HomeSocket({
        onMailboxCount: (count) => counts.push(count),
        onMailGraph: (graph) => graphs.push(graph),
        open: () => {
            const fake = new FakeSocket();
            opened.push(fake);
            return fake;
        },
        // No waiting in a test; the delays themselves are a constant, not behaviour.
        reconnectDelays: [1],
    });
    return {home, opened, counts, graphs, latest: () => opened[opened.length - 1]};
}

/** Long enough for a reconnect scheduled with a 1ms delay. */
const afterReconnect = () => new Promise((resolve) => setTimeout(resolve, 20));

test("passes on the count the server sends", () => {
    const {home, latest, counts} = socket();

    home.start();
    latest().onopen?.();
    latest().sendCount(42);

    expect(counts).toEqual([42]);
});

test("connects once, no matter how often it is started", () => {
    const {home, opened} = socket();

    home.start();
    home.start();

    expect(opened.length).toBe(1);
});

test("comes back after the connection drops", async () => {
    const {home, opened, latest, counts} = socket();

    home.start();
    latest().onopen?.();
    latest().drop();
    await afterReconnect();

    expect(opened.length).toBe(2);

    // The count the new connection reports is the one that counts.
    latest().onopen?.();
    latest().sendCount(7);
    expect(counts).toEqual([7]);
});

test("keeps trying while the server stays down", async () => {
    const {home, opened, latest} = socket();

    home.start();
    latest().drop();
    await afterReconnect();
    latest().drop();
    await afterReconnect();

    expect(opened.length).toBe(3);
});

test("stop closes it and does not reconnect", async () => {
    const {home, opened, latest} = socket();

    home.start();
    latest().onopen?.();
    const connection = latest();
    home.stop();
    await afterReconnect();

    expect(connection.closed).toBe(true);
    expect(opened.length).toBe(1);
});

test("a drop that arrives after stop is not answered either", async () => {
    const {home, opened, latest} = socket();

    home.start();
    const connection = latest();
    home.stop();
    connection.drop();
    await afterReconnect();

    expect(opened.length).toBe(1);
});

test("passes on a year of the heatmap", () => {
    const {home, latest, graphs} = socket();

    home.start();
    latest().onopen?.();
    latest().sendGraph(2026, {"2026-01-04": 3}, [2024, 2026]);

    expect(graphs).toEqual([
        {year: 2026, availableYears: [2024, 2026], days: new Map([["2026-01-04", 3]])},
    ]);
});

test("asks for a year once, and not again for the same one", () => {
    const {home, latest} = socket();

    home.start();
    latest().onopen?.();
    home.requestYear(2024);
    home.requestYear(2024);

    expect(latest().sent).toEqual([{type: "request.mail_graph", year: 2024}]);
});

test("asks for every year again after a reconnect", async () => {
    const {home, opened, latest} = socket();

    home.start();
    latest().onopen?.();
    home.requestYear(2024);
    home.requestYear(2023);

    latest().drop();
    await afterReconnect();
    latest().onopen?.();

    expect(opened.length).toBe(2);
    // The server's subscription died with the old connection, so the new one is told again.
    expect(latest().sent).toEqual([
        {type: "request.mail_graph", year: 2024},
        {type: "request.mail_graph", year: 2023},
    ]);
});
