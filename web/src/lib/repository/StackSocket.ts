import type { Mail, MailPage } from '$lib/repository/MailRepository';

/** Where the socket lives. Under `/api`, so the proxy sends it to Ktor like everything else. */
const PATH = '/api/webapp/my-stack/';

/**
 * How long a command may go unanswered before the socket counts as dead.
 *
 * A socket that is open but silent is the failure nothing else catches: no close event, no error,
 * the answer simply never comes. Without this the screen would sit there loading forever.
 */
const ANSWER_TIMEOUT = 10_000;

/**
 * How long to wait before each reconnect, and how many there are. Short first, because most drops
 * are a proxy timeout or a moment of no network and the next attempt just works; then longer, so a
 * server that is actually down is not hammered. Once they run out, whatever was waiting is failed
 * and the screen offers the reader the retry button.
 */
const RECONNECT_DELAYS = [250, 1_000, 3_000, 6_000];

/** What the stack asks the server for. */
type StackCommand = {
	type: 'load_mails';
	limit: number;
	/** Exclusive upper bound on the send time; absent for the first pack. */
	before?: string;
};

/** What the server sends down the socket. */
type StackEvent =
	| { type: 'mails'; mails: Mail[]; total: number; before?: string | null }
	| { type: 'error'; command?: string | null; message: string };

/** A command that has been asked for and not answered yet. */
type Pending = {
	frame: string;
	/** Whether it went out on the socket that is open now; false again after a reconnect. */
	sent: boolean;
	resolve: (page: MailPage) => void;
	reject: (cause: Error) => void;
};

/** Builds the socket. Injectable so the wiring can be driven without a server. */
export type SocketFactory = (url: string) => WebSocket;

/**
 * The stack screen's channel to the server.
 *
 * A socket rather than a request per pack, because the rest of the screen's traffic belongs on a
 * connection that is already open: the decisions the reader makes, and the mails that arrive while
 * they work. Bodies are the one exception and stay on `MailRepository.getContent` -- they are big,
 * the browser caches them, and nothing about them is live.
 *
 * Opened on the first ask rather than in the constructor, so this can be built while the page is
 * rendered on the server. A socket that drops with nothing outstanding is simply forgotten and the
 * next ask opens a new one; a drop with a command in flight reconnects on its own and sends that
 * command again, so a proxy timeout or a lost minute of network never reaches the reader. The
 * caller only ever hears about it once [RECONNECT_DELAYS] is used up: then the pack is rejected,
 * and asking again is the screen's retry button.
 *
 * Reconnecting is driven by what is outstanding, not by a keepalive of its own: nothing here holds
 * a connection open for a screen that is not asking for anything.
 */
export class StackSocket {
	readonly #createSocket: SocketFactory;

	#socket: WebSocket | null = null;
	/**
	 * Commands asked for and not answered yet, oldest first. The server answers every command with
	 * exactly one event, in the order the commands came in, so the answers line up with the asks by
	 * position. The day the server also sends events nobody asked for, this needs a command id.
	 */
	#pending: Pending[] = [];
	/** Which reconnect is next. Back to zero as soon as an answer arrives. */
	#attempt = 0;
	#reconnecting: ReturnType<typeof setTimeout> | null = null;
	#timeout: ReturnType<typeof setTimeout> | null = null;
	/** Set by [close]: the screen is gone, so nothing reopens the socket. */
	#closed = false;

	constructor(createSocket: SocketFactory = (url) => new WebSocket(url)) {
		this.#createSocket = createSocket;
	}

	/** The next pack of mails, newest first, ending before `before`. */
	requestMails(query: { limit: number; before?: string }): Promise<MailPage> {
		if (this.#closed) return Promise.reject(new Error('The stack socket is closed'));

		const command: StackCommand = { type: 'load_mails', ...query };

		return new Promise<MailPage>((resolve, reject) => {
			this.#pending.push({ frame: JSON.stringify(command), sent: false, resolve, reject });
			this.#flush();
		});
	}

	/** Hangs up for good and fails whatever was still on its way. Leaving the screen calls this. */
	close(): void {
		this.#closed = true;
		this.#clearTimers();

		const socket = this.#socket;
		this.#socket = null;
		socket?.close();

		this.#failPending(new Error('The stack socket was closed'));
	}

	/** Sends everything that has not gone out yet, opening the socket if it has to. */
	#flush(): void {
		if (this.#closed || this.#pending.length === 0) return;

		const socket = this.#open();
		// Still shaking hands: the open event runs this again, and the frames wait until then.
		if (socket.readyState !== WebSocket.OPEN) return;

		for (const waiting of this.#pending) {
			if (waiting.sent) continue;

			socket.send(waiting.frame);
			waiting.sent = true;
		}

		this.#armTimeout();
	}

	#open(): WebSocket {
		if (this.#socket) return this.#socket;

		const socket = this.#createSocket(socketUrl());
		this.#socket = socket;

		socket.onopen = () => this.#flush();
		socket.onmessage = (message) => this.#receive(String(message.data));
		// A socket that errors is a socket that is going: closing it here puts both cases through
		// the one path below.
		socket.onerror = () => socket.close();
		socket.onclose = () => {
			if (this.#socket !== socket) return;

			this.#drop();
			// Only reconnects for work that is waiting; an idle drop is picked up by the next ask.
			if (this.#pending.length > 0) this.#scheduleReconnect();
		};

		return socket;
	}

	#receive(data: string): void {
		let event: StackEvent;
		try {
			event = JSON.parse(data) as StackEvent;
		} catch {
			return;
		}

		const waiting = this.#pending.shift();
		if (!waiting) return;

		// An answer of any kind means the connection works, an `error` event included: that one is
		// the server turning a command down, not the socket failing.
		this.#attempt = 0;
		this.#clearTimeout();
		this.#armTimeout();

		if (event.type === 'mails') waiting.resolve({ mails: event.mails, total: event.total });
		else waiting.reject(new Error(event.message));
	}

	/** Puts the socket aside so the next flush builds a new one, and marks its frames unsent. */
	#drop(): void {
		this.#socket = null;
		this.#clearTimeout();
		for (const waiting of this.#pending) waiting.sent = false;
	}

	#scheduleReconnect(): void {
		if (this.#closed || this.#reconnecting) return;

		const delay = RECONNECT_DELAYS[this.#attempt];
		if (delay === undefined) {
			// Out of attempts. The counter goes back to zero, so the retry button starts over.
			this.#attempt = 0;
			this.#failPending(new Error('The stack socket could not be reached'));
			return;
		}

		this.#attempt += 1;
		this.#reconnecting = setTimeout(() => {
			this.#reconnecting = null;
			this.#flush();
		}, delay);
	}

	/** Watches the oldest command out there; a socket that has gone silent is treated as dead. */
	#armTimeout(): void {
		if (this.#timeout || this.#pending.length === 0) return;

		this.#timeout = setTimeout(() => {
			this.#timeout = null;
			// Only closes it: dropping the socket and reconnecting is what the close handler does,
			// and doing it here as well would spend one of the attempts on nothing.
			this.#socket?.close();
		}, ANSWER_TIMEOUT);
	}

	#clearTimeout(): void {
		if (this.#timeout) clearTimeout(this.#timeout);
		this.#timeout = null;
	}

	#clearTimers(): void {
		this.#clearTimeout();
		if (this.#reconnecting) clearTimeout(this.#reconnecting);
		this.#reconnecting = null;
	}

	#failPending(cause: Error): void {
		const pending = this.#pending;
		this.#pending = [];
		for (const waiting of pending) waiting.reject(cause);
	}
}

/** The socket's absolute url, off the page's own origin, `ws` or `wss` to match it. */
function socketUrl(): string {
	const url = new URL(PATH, location.href);
	url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';

	return url.toString();
}
