import {HomeSocket} from "$lib/repository/HomeSocket";

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

    private readonly socket: HomeSocket;

    /** Open [connect] handles. The socket lives exactly as long as there is one. */
    private watchers = 0;

    constructor(socket?: HomeSocket) {
        this.socket = socket ?? new HomeSocket({
            onMailboxCount: (count) => (this.mailboxCount = count),
        });
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
}
