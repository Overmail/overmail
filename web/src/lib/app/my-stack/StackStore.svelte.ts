import {
	mailRepository,
	type Mail,
	type MailPage,
	type MailRepository
} from '$lib/repository/MailRepository';
import { StackSocket } from '$lib/repository/StackSocket';
import { mailBodyText } from '$lib/app/mails/body';
import type { EmailClassification } from '$lib/app/my-stack/classification';

/**
 * How many mails to keep in front of the top card. Topped up towards this number rather than read
 * in packs: the stack asks for what it is short of, whenever it is short of it, so the reader is
 * never waiting on a request that happens to be big.
 *
 * A mailbox that runs out simply stays below it -- see `exhausted`, after which nothing asks again.
 */
const MIN_AHEAD = 15;

/**
 * How far ahead of the top card the bodies are fetched. Fewer than [MIN_AHEAD]: a body is a request
 * of its own and worth having early, but not for mails the reader may never reach.
 */
const BODIES_AHEAD = 10;

/** What the store is doing. `error` means a request failed; [StackStore.retry] takes it back. */
export type StackStoreStatus = 'idle' | 'loading' | 'error';

/** One mail on the stack: what the listing said, its body, and what has been decided about it. */
export type StackEntry = {
	mail: Mail;
	/** Absent while the body's own request is still out, see [StackStore]. */
	body?: string;
	/** Absent as long as the mail is still waiting for a decision. */
	classification?: EmailClassification;
	/** The tags the mail carries, seeded from the listing and edited from here on. */
	tags: string[];
};

/**
 * The mails to decide on, newest first, and which of them is on top.
 *
 * Kept at [MIN_AHEAD] mails in front of the top card: every time the top moves, whatever is short
 * of that number is asked for, so the requests are as small as the reader is fast and there is
 * always a stack to work through. Counted from the top rather than over the whole list, because
 * skipping a mail leaves it in the list: a reader pressing on without deciding runs the stack down
 * just as one deciding on every mail does.
 *
 * A mailbox has an end, and the stack reaches it: once the server answers with less than was asked
 * for there is nothing older left, nothing asks again, and what is in front of the top card is all
 * there will be.
 *
 * The packs come over the screen's own socket, see [StackSocket]: everything but the bodies goes
 * that way. The cursor is the send time of the oldest mail read so far, handed back as `before` --
 * no request makes the database count through the rows above it, and a mail arriving while the
 * stack is being worked through does not shift the pack boundaries.
 *
 * Bodies are the exception: their own plain request per mail, run [BODIES_AHEAD] ahead of the top
 * card and topped up whenever the stack moves -- the same rule as the mails, so a card is never
 * waiting on its own body. They are not part of a pack because that would be tens of thousands of
 * characters per mail on the wire before anything is on screen, and unlike a pack they are worth
 * letting the browser cache.
 *
 * The top of the stack lives here rather than in the page, because the refill is measured from it.
 * Decisions go nowhere else yet: [classify] and [setTags] write to this list only, which is the
 * seam the archive and the tag repositories plug into later.
 */
export class StackStore {
	/** Every mail read so far, newest first, decided ones included. */
	entries = $state<StackEntry[]>([]);
	status = $state<StackStoreStatus>('idle');
	/**
	 * Whether a pack has ever come back. An empty list means nothing until it has -- until then
	 * nothing has been asked for, and a full mailbox would read as a finished stack.
	 */
	initialized = $state(false);

	/** The mail on top of the stack: the one a decision applies to. */
	topId = $state<string | undefined>(undefined);

	/** Every mail still waiting for a decision, in the order the stack shows them. */
	waiting: StackEntry[] = $derived(this.entries.filter((entry) => !entry.classification));

	/** The mail on top, or none while the first pack is still on its way. */
	top: StackEntry | undefined = $derived(this.entries[this.#indexOf(this.topId)]);

	/**
	 * The top mail and the undecided ones behind it: what is left to work through from here. Ends
	 * at the oldest mail read so far, which is what makes it the measure for the refill.
	 */
	ahead: StackEntry[] = $derived.by(() => {
		const from = this.#indexOf(this.topId);
		const rest = from < 0 ? this.entries : this.entries.slice(from);
		return rest.filter((entry) => !entry.classification);
	});

	readonly #socket: StackSocket;
	readonly #repository: MailRepository;
	readonly #minAhead: number;

	/** Where the next pack carries on: exclusive upper bound on the send time. */
	#cursor: string | undefined;
	/** Ids in [entries], so a pack overlapping the cursor's second adds nothing twice. */
	#known = new Set<string>();
	/** Whether a pack is on the wire, so a burst of decisions does not fire one per decision. */
	#loading = false;
	/** Whether the last pack failed, which stops the refill until [retry]. */
	#failed = false;
	/** Mails whose body has been asked for, so it is asked for once. */
	#bodiesRequested = new Set<string>();
	/** Whether the server has run dry, so nothing asks it again. */
	#exhausted = false;

	constructor(
		socket: StackSocket = new StackSocket(),
		repository: MailRepository = mailRepository,
		minAhead: number = MIN_AHEAD
	) {
		this.#socket = socket;
		this.#repository = repository;
		this.#minAhead = minAhead;
	}

	/** Hangs up. Leaving the screen is what calls this; the mails stay in the list. */
	close(): void {
		this.#socket.close();
	}

	/**
	 * Asks for whatever the stack is short of [MIN_AHEAD], and for the bodies coming up. Cheap to
	 * call whenever the stack moves: it starts at most one request and none once the stack is full
	 * enough or the mailbox has run out.
	 */
	ensureFilled(): void {
		this.#ensureBodies();

		const missing = this.#minAhead - this.ahead.length;
		if (missing > 0) this.#load(missing);
	}

	/** Asks again after a failed request. */
	retry(): void {
		if (!this.#failed) return;

		this.#failed = false;
		this.#syncStatus();
		this.ensureFilled();
	}

	/** Files a decision on the top mail and moves on. Local for now, see [StackStore]. */
	classify(to: EmailClassification['to']): void {
		const top = this.top;
		if (!top) return;

		const below = this.#indexOf(this.topId) + 1;
		top.classification = { to };
		// Nothing below means the ones skipped over further up are all that is left; the stack
		// comes round to them rather than ending on a mail nobody decided on.
		this.topId = (this.#undecidedFrom(below) ?? this.waiting[0])?.mail.id;
		this.ensureFilled();
	}

	/**
	 * Moves to the next mail without deciding on this one, so it stays in the stack. Refills like a
	 * decision does: what runs the stack down is the top moving, not the decision.
	 */
	skip(): void {
		const next = this.#undecidedFrom(this.#indexOf(this.topId) + 1);
		if (next) this.topId = next.mail.id;

		this.ensureFilled();
	}

	/**
	 * Back to the mail before the top one. Its decision is dropped on the way, so going back is
	 * also the undo.
	 */
	back(): void {
		const at = this.#indexOf(this.topId);
		const from = (at < 0 ? this.entries.length : at) - 1;
		if (from < 0) return;

		const entry = this.entries[from];
		entry.classification = undefined;
		this.topId = entry.mail.id;
	}

	/** Replaces the tags of the top mail. Local for now, see [StackStore]. */
	setTags(tags: string[]): void {
		if (this.top) this.top.tags = [...tags];
	}

	/** Where a mail sits in [entries], or -1 for none and for no mail on top. */
	#indexOf(id: string | undefined): number {
		return id === undefined ? -1 : this.entries.findIndex((entry) => entry.mail.id === id);
	}

	/** The first mail from [from] downwards that is still waiting for a decision. */
	#undecidedFrom(from: number): StackEntry | undefined {
		for (let index = Math.max(from, 0); index < this.entries.length; index += 1) {
			if (!this.entries[index].classification) return this.entries[index];
		}

		return undefined;
	}

	#load(missing: number): void {
		if (this.#loading || this.#failed || this.#exhausted) return;

		// One more than is missing once there is a cursor: it sits a second past the oldest mail
		// held, so the answer opens with that mail again, see the cursor below.
		const overlap = this.#cursor === undefined ? 0 : 1;
		void this.#loadPage(missing + overlap);
	}

	async #loadPage(limit: number): Promise<void> {
		this.#loading = true;
		this.#syncStatus();

		try {
			// Newest first is all the socket serves, so the ask needs no sort of its own.
			const page = await this.#socket.requestMails({ limit, before: this.#cursor });
			this.#apply(page, limit);
		} catch {
			this.#failed = true;
		} finally {
			this.#loading = false;
			this.#syncStatus();
		}
	}

	#apply(page: MailPage, limit: number): void {
		this.initialized = true;

		const fresh = page.mails.filter((mail) => !this.#known.has(mail.id));
		for (const mail of fresh) this.#known.add(mail.id);

		// Seeded with the tags the mail already carries, so the card shows what is on it rather
		// than an empty row that a tag edit would then look like it created.
		const added = fresh.map((mail) => ({ mail, tags: mail.tags.map((tag) => tag.name) }));
		this.entries = [...this.entries, ...added];

		// An answer the server could not fill means there is nothing older left. Said outright
		// rather than inferred from a count, which can be out of date by the time it is read.
		if (page.mails.length < limit) this.#exhausted = true;

		const last = page.mails.at(-1);
		if (last) {
			// `before` is exclusive and send times are stored at second precision, so the cursor
			// goes a second past the last mail and the overlap is dropped by id above. Cutting at
			// the send time itself would lose the rest of that second whenever a pack ends inside
			// one.
			const overfullSecond = fresh.length === 0 && page.mails.length === limit;
			// That case means one second holds more mail than was asked for. Stepping into the
			// second is the only way on; it is the one way this list can miss a mail.
			this.#cursor = overfullSecond ? last.sent_at : shiftSecond(last.sent_at, 1);
		}

		// The first answer is what puts a mail on top, and a stack that ran empty gets its top from
		// the mails that refilled it. Nothing outside knows when that happens.
		if (this.topId === undefined) this.topId = this.waiting[0]?.mail.id;

		this.#ensureBodies();
	}

	/** Fetches the bodies of the mails coming up that have none, one request each. */
	#ensureBodies(): void {
		// From the top downwards: the cards on screen come first, and the rest of the pack follows
		// while the reader is still working through them. A mail that was skipped over had its
		// body fetched while it was on top, and gets it again when the stack comes round to it.
		for (const entry of this.ahead.slice(0, BODIES_AHEAD)) {
			if (entry.body !== undefined) continue;

			const id = entry.mail.id;
			if (this.#bodiesRequested.has(id)) continue;

			this.#bodiesRequested.add(id);
			void this.#loadBody(id);
		}
	}

	async #loadBody(id: string): Promise<void> {
		try {
			const content = await this.#repository.getContent(id);
			const entry = this.entries[this.#indexOf(id)];
			if (entry) entry.body = mailBodyText(content);
		} catch {
			// Left without a body and asked for again the next time the stack moves: one mail whose
			// body did not arrive must not stop the stack, and the header alone is enough to
			// decide on most mail.
			this.#bodiesRequested.delete(id);
		}
	}

	#syncStatus(): void {
		this.status = this.#failed ? 'error' : this.#loading ? 'loading' : 'idle';
	}
}

function shiftSecond(isoTimestamp: string, seconds: number): string {
	return new Date(Date.parse(isoTimestamp) + seconds * 1000).toISOString();
}
