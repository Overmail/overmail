/**
 * The part of `WebSocket` this uses. A test hands in its own; nothing else has a reason to.
 */
export type SocketLike = {
    send(data: string): void;
    close(): void;
    onopen: (() => void) | null;
    onclose: ((event: {wasClean: boolean}) => void) | null;
    onmessage: ((event: {data: string}) => void) | null;
};

/**
 * How long to wait before trying again, per attempt in a row; the last one repeats. A dropped
 * connection is usually the server restarting or a laptop waking up, so the first retry is quick
 * and a lasting outage is not hammered.
 */
const RECONNECT_DELAYS = [1_000, 2_000, 5_000, 10_000, 30_000];

/**
 * A socket that gets itself back up.
 *
 * Everything about the connection lives in here -- connecting, backing off, reconnecting, and
 * telling the owner it has to say again what it wants. The repositories on top only deal in
 * messages.
 *
 * The server's side of a subscription dies with the connection, so [onOpen] is where a caller
 * re-sends what it is subscribed to. It runs on the first connection as well: what a fresh socket
 * and a reconnected one have to be told is the same thing.
 */
export class ReconnectingSocket<Message> {
    private socket: SocketLike | null = null;
    private retry: ReturnType<typeof setTimeout> | null = null;

    /** How many closes in a row without a connection in between; picks the delay. */
    private failures = 0;

    /** Set by [stop], so a close it caused is not answered with a reconnect. */
    private isStopped = true;

    private readonly url: string;
    private readonly onMessage: (message: Message) => void;
    private readonly onOpen: () => void;
    private readonly open: (url: string) => SocketLike;
    private readonly delays: number[];

    constructor(config: {
        url: string;
        onMessage: (message: Message) => void;
        /** Say again what this connection is supposed to be sending. */
        onOpen?: () => void;
        /** Defaults to a real browser socket. */
        open?: (url: string) => SocketLike;
        /** Overridden in tests, which have no second to wait. */
        reconnectDelays?: number[];
    }) {
        this.url = config.url;
        this.onMessage = config.onMessage;
        this.onOpen = config.onOpen ?? (() => {});
        this.open = config.open ?? ((url) => new WebSocket(url) as unknown as SocketLike);
        this.delays = config.reconnectDelays ?? RECONNECT_DELAYS;
    }

    /** Connects, and keeps it that way until [stop]. Calling it again while up does nothing. */
    start() {
        this.isStopped = false;
        if (this.socket !== null) return;
        this.connect();
    }

    /** Closes for good: no reconnect follows this one. */
    stop() {
        this.isStopped = true;
        if (this.retry !== null) {
            clearTimeout(this.retry);
            this.retry = null;
        }
        const socket = this.socket;
        this.socket = null;
        this.failures = 0;
        socket?.close();
    }

    /** True while there is a connection to send on. */
    get isOpen(): boolean {
        return this.socket !== null;
    }

    /**
     * Sends [message] if there is a connection, and drops it if there is none -- a caller that
     * has state to keep in sync re-sends it from [onOpen] rather than queueing it here, because
     * what was worth sending before a reconnect is rarely still worth sending after one.
     */
    send(message: unknown) {
        this.socket?.send(JSON.stringify(message));
    }

    private connect() {
        const socket = this.open(this.url);
        this.socket = socket;

        socket.onopen = () => {
            // Reached the server, so a later drop starts counting from the short delay again.
            this.failures = 0;
            this.onOpen();
        };

        socket.onmessage = (event) => {
            this.onMessage(JSON.parse(event.data) as Message);
        };

        socket.onclose = () => {
            if (this.socket !== socket) return; // an old socket reporting after a stop
            this.socket = null;
            if (this.isStopped) return;

            const delay = this.delays[Math.min(this.failures, this.delays.length - 1)];
            this.failures++;
            this.retry = setTimeout(() => {
                this.retry = null;
                if (!this.isStopped) this.connect();
            }, delay);
        };
    }
}
