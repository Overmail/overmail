import type {MailParticipant} from '$lib/repository/MailRepository';

const PATH = '/api/webapp/agent/process';

/** How long to wait before dialling again after the socket dropped. */
const RECONNECT_DELAY_MS = 3000;

/** Whether what is left in the queue is a mailbox being worked through or the day's post. */
export type AgentQueueMode = 'backlog' | 'live';

/**
 * The passes a mail goes through, in the order it goes through them. Each is a model call of its
 * own, so this is what moves while a single mail is being worked on.
 */
export type AgentStep =
	/** Reading off who the mail came from. */
	| 'origin'
	/** Suggesting what to file it under. */
	| 'tags'
	/** Working out which matter it continues. */
	| 'thread'
	/** Going over the filing again with the neighbouring mails in view. */
	| 'review';

/** What the agent has in its hands, seen from us. */
export type AgentWorkState =
	/** Nothing to do: the queue has run dry, or the agent is down. */
	| 'idle'
	/** Busy with somebody else's mail, so ours wait their turn. Which mail is not disclosed. */
	| 'pending'
	/** Working on one of our mails, named in `email_id`. */
	| 'processing';

/** One push off the agent socket. */
export type AgentProcessStatus = {
	queue: {
		mode: AgentQueueMode;
		/** Mails of ours the agent has been through. */
		processed: number;
		/** Mails of ours still waiting, the one being worked on right now included. */
		queued: number;
		/** Send time of the oldest waiting mail, ISO-8601; null when none is waiting. */
		oldest_queued_at: string | null;
	};
	work: {
		state: AgentWorkState;
		/**
		 * The mail being worked on. Only ever one of ours and therefore only set while `state` is
		 * `processing`: on a foreign mail the agent is reported as busy and nothing else.
		 */
		email_id?: string | null;
		/** Empty for a mail that carries no subject line. */
		subject?: string | null;
		sender?: MailParticipant | null;
		/** Which pass the mail is in right now; a mail goes through several. */
		step?: AgentStep | null;
	};
};

/** A running subscription; call [close] to hang up and stop reconnecting. */
export type AgentProcessConnection = {
	close(): void;
};

/** What a subscriber gets told, beyond the status itself. */
export type AgentProcessHandlers = {
	onStatus(status: AgentProcessStatus): void;
	/** The socket is open. The server sends the current state right after, unasked. */
	onOpen?(): void;
	/** The socket dropped; a reconnect is already scheduled. */
	onDrop?(): void;
};

/** Watching the mail agent work. */
export class AgentRepository {
	/**
	 * Opens the status socket and keeps it open: the server pushes the current state on connect
	 * and then whenever it changes, and a dropped connection is dialled again.
	 *
	 * Browser only -- there is no socket to open while rendering on the server. The session
	 * cookie goes along by itself, same origin.
	 */
	watchProcess(handlers: AgentProcessHandlers): AgentProcessConnection {
		let socket: WebSocket | null = null;
		let retry: ReturnType<typeof setTimeout> | null = null;
		// Set by close(), so a socket we hung up on does not schedule a reconnect on its way out.
		let hungUp = false;

		const dial = () => {
			const url = new URL(PATH, location.href);
			url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';

			socket = new WebSocket(url);
			socket.onopen = () => handlers.onOpen?.();
			socket.onmessage = (event) => handlers.onStatus(JSON.parse(event.data) as AgentProcessStatus);
			// No `onerror` handling: an error is always followed by a close, and reconnecting from
			// both would dial twice.
			socket.onclose = () => {
				if (hungUp) return;
				handlers.onDrop?.();
				retry = setTimeout(dial, RECONNECT_DELAY_MS);
			};
		};

		dial();

		return {
			close() {
				hungUp = true;
				if (retry !== null) clearTimeout(retry);
				socket?.close();
			}
		};
	}
}

export const agentRepository = new AgentRepository();
