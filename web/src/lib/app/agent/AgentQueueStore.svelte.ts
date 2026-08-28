/**
 * What the agent is doing, and the way to give it something to do.
 *
 * One socket for the whole screen, opened once and kept: the panel shows what is owed, the button in
 * the corner shows that something is running, and both are the same two numbers. A store rather than
 * state in a component, because the two live in different components -- the fab is visible exactly
 * when the panel is not.
 *
 * Nothing here runs a classification. Pressing a button puts mails in the queue on the server and
 * the walk over it happens there, which is what makes closing the panel -- or the tab -- harmless.
 */

/** How the screen is doing, which is not how the agent is doing. */
export type AgentStatus = 'connecting' | 'live' | 'offline';

/** What the last press of a button came to. */
export type QueuedAnswer = {
	/** How many mails the request came to. Fewer than asked for where the mailbox ran out. */
	asked: number;
	queued: number;
	/** Of those, how many were waiting already. */
	already_waiting: number;
};

type AgentEvent =
	| { type: 'queue'; pending: number; current?: string | null }
	| ({ type: 'queued' } & QueuedAnswer);

/** How long before reopening a socket that dropped, per attempt; the last one repeats. */
const RECONNECT_DELAYS = [1_000, 2_000, 5_000, 10_000];

class AgentQueueStore {
	#socket: WebSocket | null = null;
	#attempt = 0;
	#reconnecting: ReturnType<typeof setTimeout> | null = null;
	/** How many components have asked for the socket. It closes when the last of them goes away. */
	#users = 0;

	status = $state<AgentStatus>('connecting');

	/** Mails of this reader still waiting for the agent, the one in progress included. */
	pending = $state(0);

	/** The mail the agent has open right now, null while it is between mails. */
	currentMailId = $state<string | null>(null);

	/** What the last press of a button came to, null until one has been pressed. */
	lastAnswer = $state<QueuedAnswer | null>(null);

	/** Whether the agent is working, which is the one thing the button in the corner shows. */
	get isWorking(): boolean {
		return this.currentMailId !== null || this.pending > 0;
	}

	/**
	 * Opens the socket, or joins the one already open.
	 *
	 * Counted rather than idempotent: the fab and the panel both want it, and the one that unmounts
	 * first must not close it under the other.
	 */
	open(): void {
		this.#users++;
		if (this.#socket) return;

		this.#connect();
	}

	/** Gives up one claim on the socket, closing it once nobody holds one. */
	close(): void {
		this.#users = Math.max(0, this.#users - 1);
		if (this.#users > 0) return;

		if (this.#reconnecting) clearTimeout(this.#reconnecting);
		this.#reconnecting = null;

		const socket = this.#socket;
		this.#socket = null;
		socket?.close();
	}

	/** Asks for the newest mails to be read, whether or not they have been read before. */
	processNewest(): void {
		this.#send({ type: 'process_newest' });
	}

	/** Asks for the newest mails that no run has ever touched. */
	processUnclassified(): void {
		this.#send({ type: 'process_unclassified' });
	}

	#send(command: { type: string }): void {
		if (this.#socket?.readyState !== WebSocket.OPEN) return;

		this.lastAnswer = null;
		this.#socket.send(JSON.stringify(command));
	}

	#connect(): void {
		const socket = new WebSocket(agentUrl());
		this.#socket = socket;

		socket.addEventListener('open', () => {
			this.#attempt = 0;
			this.status = 'live';
		});

		socket.addEventListener('message', (event) => {
			if (typeof event.data !== 'string') return;

			const message = JSON.parse(event.data) as AgentEvent;

			if (message.type === 'queue') {
				this.pending = message.pending;
				this.currentMailId = message.current ?? null;
			} else {
				this.lastAnswer = message;
			}
		});

		socket.addEventListener('close', () => {
			this.#socket = null;
			// Whoever is still holding a claim wants it back; nobody holding one means we closed it.
			if (this.#users === 0) return;

			this.status = 'offline';
			this.#scheduleReconnect();
		});
	}

	#scheduleReconnect(): void {
		if (this.#users === 0 || this.#reconnecting) return;

		const delay = RECONNECT_DELAYS[Math.min(this.#attempt, RECONNECT_DELAYS.length - 1)];
		this.#attempt++;
		this.#reconnecting = setTimeout(() => {
			this.#reconnecting = null;
			if (this.#users > 0) this.#connect();
		}, delay);
	}
}

function agentUrl(): string {
	const url = new URL('/api/webapp/agent', location.href);
	url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';

	return url.toString();
}

/** One agent per screen, so the fab and the panel cannot disagree about what it is doing. */
export const agentQueue = new AgentQueueStore();
