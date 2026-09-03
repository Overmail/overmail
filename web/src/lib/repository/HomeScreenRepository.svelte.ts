import {SvelteMap} from "svelte/reactivity";
import {HomeSocket, type MailGraph} from "$lib/repository/HomeSocket";

/**
 * What the home screen shows, kept current by a socket.
 *
 * The socket is only up while something is actually watching: [connect] hands back the release,
 * and the last one to let go closes it. A page therefore does
 * `$effect(() => repositories.home.connect())` and is done.
 */
export class HomeScreenRepository {
    /** Mails in the mailbox -- everything not archived. Null until the server has said. */
    mailboxCount: number | null = $state(null);

    /**
     * Every year the mailbox has mail in, oldest first, as the last graph reported them. Empty
     * until the first one lands, so a year switcher appears filled instead of flashing a single
     * year first.
     */
    availableYears: number[] = $state([]);

    /** The years that arrived, by year. A year is in here once, however often it is re-sent. */
    private readonly graphs = new SvelteMap<number, ReadonlyMap<string, number>>();

    private readonly socket: HomeSocket;

    /** Open [connect] handles. The socket lives exactly as long as there is one. */
    private watchers = 0;

    constructor(socket?: HomeSocket) {
        this.socket = socket ?? new HomeSocket({
            onMailboxCount: (count) => (this.mailboxCount = count),
            onMailGraph: (graph) => this.receive(graph),
        });
    }

    /**
     * Mails per day of [year], or null while they are on their way. Safe to call while
     * rendering: it changes nothing, see [requestYear].
     */
    graph(year: number): ReadonlyMap<string, number> | null {
        return this.graphs.get(year) ?? null;
    }

    /**
     * Makes sure [year] is on its way and stays up to date. From an effect, not while rendering
     * -- it talks to the server. The current year needs no request; it arrives with the socket.
     */
    requestYear(year: number) {
        this.socket.requestYear(year);
    }

    /** Starts watching; call the returned function to stop. Safe to nest. */
    connect(): () => void {
        this.watchers++;
        if (this.watchers === 1) this.socket.start();

        let released = false;
        return () => {
            // Guarded: an effect that re-runs must not release the same handle twice and close a
            // socket somebody else is still watching.
            if (released) return;
            released = true;
            this.watchers--;
            if (this.watchers === 0) this.socket.stop();
        };
    }

    private receive(graph: MailGraph) {
        this.graphs.set(graph.year, graph.days);
        this.availableYears = graph.availableYears;
    }
}
