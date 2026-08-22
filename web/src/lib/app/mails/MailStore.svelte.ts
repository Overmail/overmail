import {
	mailRepository,
	type Mail,
	type MailPage,
	type MailPageQuery,
	type MailRepository
} from '$lib/repository/MailRepository';

/** How many mails one request asks for. The server caps this at 1000. */
const PAGE_SIZE = 100;

/** What the store is doing. `error` means a request failed; [MailStore.retry] takes it back. */
export type MailStoreStatus = 'idle' | 'loading' | 'error';

/** Which end of the mailbox a stretch of loaded mails grows from. */
type Edge = 'head' | 'tail';

/**
 * The mailbox by position, filled from both ends.
 *
 * [entries] is as long as the mailbox as soon as the first page has come back, so a virtualized
 * list can be the right length before it knows what is in it. A hole in it is a mail that exists
 * and has not been fetched; scrolling onto one is what fetches it.
 *
 * Both ends are read with cursors rather than an offset, so no request makes the database count
 * through the rows in front of it. The price is that a stretch can only grow outward from
 * something already loaded, which is why there are two of them: jumping to the bottom of a
 * mailbox with thousands in it takes one request from the `asc` end instead of a walk down from
 * the top. Landing in the middle still walks, one page per round trip, from whichever end is
 * nearer.
 *
 * Everything that changes the list goes through [upsert] and [remove], which touch no network at
 * all -- that is the seam a live connection plugs into later. A websocket, an SSE stream or a
 * poller all end up calling the same two methods, and a table picks the change up because
 * [entries] is `$state`.
 */
export class MailStore {
	/** The mailbox by position, newest first; `undefined` for a mail that is not loaded. */
	entries = $state.raw<(Mail | undefined)[]>([]);
	/** How many mails the mailbox holds. 0 until the first page came back. */
	total = $state(0);
	status = $state<MailStoreStatus>('idle');
	/**
	 * Whether a page has ever come back. An empty list means nothing until it has -- on the server
	 * nothing has been asked for yet, and a mailbox with thousands in it would render as empty.
	 */
	initialized = $state(false);

	/** The mails that are loaded, in mailbox order -- what a table is built from. */
	loaded: Mail[] = $derived(this.entries.filter((mail) => mail !== undefined));

	readonly #repository: MailRepository;
	readonly #pageSize: number;

	/** `entries[0 .. #head - 1]` are loaded: the newest mails, read `desc`. */
	#head = 0;
	/** `entries[total - #tail .. total - 1]` are loaded: the oldest mails, read `asc`. */
	#tail = 0;
	/** Where each end carries on, as the exclusive cursor its next request takes. */
	#cursor: Partial<Record<Edge, string>> = {};
	/** Ids in [entries], so a page overlapping an end adds nothing twice. */
	#known = new Set<string>();
	/** Ends with a request out, so a scroll burst does not fire one per frame. */
	#inFlight = new Set<Edge>();
	/** Ends whose last request failed, skipped until [retry]. */
	#failed = new Set<Edge>();
	/** Ends the server has run dry, so nothing asks them again. */
	#exhausted = new Set<Edge>();
	/** Bumped by [reset], so a request still in flight for the old list lands nowhere. */
	#generation = 0;

	/**
	 * Narrows the whole store to the mails that sit in some thread, or to the ones that sit in
	 * none. Undefined for the mailbox as it is. Part of every request and of the count that comes
	 * back with them, so a narrowed store is a list in its own right -- which is how the grouped
	 * view gets a stretch of just the unfiled mails.
	 */
	readonly #filed: boolean | undefined;

	constructor(
		repository: MailRepository = mailRepository,
		pageSize: number = PAGE_SIZE,
		options: { filed?: boolean } = {}
	) {
		this.#repository = repository;
		this.#pageSize = pageSize;
		this.#filed = options.filed;
	}

	/**
	 * Makes sure the mails between [from] and [to] are on their way. Cheap to call on every scroll
	 * frame: it starts at most one request, and none once the range is covered.
	 */
	ensureRange(from: number, to: number): void {
		// Nothing is known before the first page, and it is that page which reports the length.
		if (this.total === 0) {
			this.#load('head');
			return;
		}

		const first = Math.max(0, Math.min(from, to));
		const last = Math.min(this.total - 1, Math.max(from, to));
		if (first > last) return;

		// How much further each end would have to reach to cover the range. Either being done
		// already means the range is loaded.
		const fromHead = last + 1 - this.#head;
		const fromTail = this.total - first - this.#tail;
		if (fromHead <= 0 || fromTail <= 0) return;

		// The nearer end, and the other one when that one has nothing left to give -- an end that
		// ran dry must not leave the range unread while the other could still cover it.
		const nearer = fromHead <= fromTail ? 'head' : 'tail';
		const other = nearer === 'head' ? 'tail' : 'head';
		this.#load(this.#canLoad(nearer) ? nearer : other);
	}

	/**
	 * Extends the newest-first stretch by a page. What a reader walking down the list needs, and
	 * the only way on when the row order is not the mailbox's -- see the grouped table.
	 */
	loadMore(): void {
		this.#load('head');
	}

	/** Asks again for whatever failed. */
	retry(): void {
		const edges = [...this.#failed];
		this.#failed.clear();
		this.#syncStatus();
		for (const edge of edges) this.#load(edge);
	}

	/** Throws the list away and starts over. */
	reset(): void {
		this.#generation += 1;
		this.#inFlight.clear();
		this.#failed.clear();
		this.#exhausted.clear();
		this.#cursor = {};
		this.#known = new Set();
		this.#head = 0;
		this.#tail = 0;
		this.entries = [];
		this.total = 0;
		this.initialized = false;
		this.status = 'idle';
	}

	/**
	 * Puts a mail into the list, replacing the one that carries its id and moving it if its send
	 * time changed.
	 *
	 * Placed only where its neighbours are known. A mail that belongs inside a hole has no index
	 * anybody can name, so the mailbox only grows by one and the hole keeps it -- reading that
	 * stretch fetches the mail with its page.
	 */
	upsert(mail: Mail): void {
		const next = this.entries.slice();
		const at = next.findIndex((known) => known?.id === mail.id);

		if (at >= 0) next.splice(at, 1);
		// A mail nobody has seen grows the mailbox, wherever in it the mail ends up sitting.
		else this.total += 1;

		const insertAt = insertionIndex(next, mail);
		if (next[insertAt - 1] !== undefined || next[insertAt] !== undefined) {
			next.splice(insertAt, 0, mail);
			this.#known.add(mail.id);
		} else {
			this.#known.delete(mail.id);
		}

		this.#commit(next);
	}

	/** Takes a mail out of the list. */
	remove(id: string): void {
		const next = this.entries.slice();
		const at = next.findIndex((known) => known?.id === id);
		if (at >= 0) next.splice(at, 1);

		// Counted down even for a mail below the loaded window: it was in the mailbox either way,
		// and the list is sized from this number.
		if (this.total > 0) this.total -= 1;

		this.#known.delete(id);
		this.#commit(next);
	}

	#load(edge: Edge): void {
		if (!this.#canLoad(edge)) return;
		void this.#loadFrom(edge);
	}

	#canLoad(edge: Edge): boolean {
		if (this.#inFlight.has(edge) || this.#failed.has(edge)) return false;
		if (this.#exhausted.has(edge)) return false;
		// The two ends have met: there is nothing between them left to ask for.
		if (this.total > 0 && this.#head + this.#tail >= this.total) return false;

		return true;
	}

	async #loadFrom(edge: Edge): Promise<void> {
		const generation = this.#generation;
		this.#inFlight.add(edge);
		this.#syncStatus();

		try {
			const query = this.#queryFor(edge);
			const page = await this.#repository.listMails(query);
			// Reset while this was in flight: these rows belong to a list that no longer exists.
			if (generation !== this.#generation) return;
			this.#apply(edge, page, query.before === undefined && query.after === undefined);
		} catch {
			if (generation === this.#generation) this.#failed.add(edge);
		} finally {
			if (generation === this.#generation) {
				this.#inFlight.delete(edge);
				this.#syncStatus();
			}
		}
	}

	#queryFor(edge: Edge): MailPageQuery {
		const window = { limit: this.#pageSize, filed: this.#filed };

		return edge === 'head'
			? { ...window, sort: 'desc' as const, before: this.#cursor.head }
			: { ...window, sort: 'asc' as const, after: this.#cursor.tail };
	}

	#apply(edge: Edge, page: MailPage, isUnbounded: boolean): void {
		this.initialized = true;

		// Only off a request that carried no cursor. `total` counts the window that was asked for,
		// and every later request narrows that window to what is left beyond its cursor -- taking
		// it from those would shrink the list by a page each time until the two ends appear to
		// have met, which is a wall somewhere in the middle of the mailbox.
		if (isUnbounded) this.total = page.total;

		const next = this.entries.slice(0, this.total);
		while (next.length < this.total) next.push(undefined);

		const fresh = page.mails.filter((mail) => !this.#known.has(mail.id));
		for (const mail of fresh) this.#known.add(mail.id);

		if (edge === 'head') {
			// Descending, so the first row is the newest of the ones still missing at the top.
			fresh.forEach((mail, at) => (next[this.#head + at] = mail));
		} else {
			// Ascending, so the first row is the oldest one there is, which sits at the very end.
			fresh.forEach((mail, at) => (next[this.total - 1 - this.#tail - at] = mail));
		}

		// A page the server could not fill means this end has read everything on its side. Said
		// outright rather than left to the two ends meeting: a count taken once at the start can
		// be out of date, and an end that keeps asking would poll an empty answer forever.
		if (page.mails.length < this.#pageSize) this.#exhausted.add(edge);

		const last = page.mails.at(-1);
		if (last) {
			// The cursors are exclusive and send times are stored at second precision, so each is
			// put a second past the last row -- reading down, `before` a second later; reading up,
			// `after` a second earlier -- and the overlap dropped by id above. Cutting at the send
			// time itself would lose the rest of that second whenever a page ends inside one.
			const overfullSecond = fresh.length === 0 && page.mails.length === this.#pageSize;
			// That case means a single second holds more mail than a page does. Stepping past the
			// second is the only way on; it is the one way this list can miss a mail.
			this.#cursor[edge] = overfullSecond
				? last.sent_at
				: shiftSecond(last.sent_at, edge === 'head' ? 1 : -1);
		}

		this.#commit(next);
	}

	/**
	 * Writes the list back and reads the two ends off it again. Recounted rather than tracked: a
	 * page lands at a known index, but an upsert shifts everything below it, and a count over the
	 * array cannot drift out of step with the array.
	 */
	#commit(entries: (Mail | undefined)[]): void {
		entries.length = this.total;

		let head = 0;
		while (head < entries.length && entries[head] !== undefined) head += 1;

		let tail = 0;
		while (tail < entries.length - head && entries[entries.length - 1 - tail] !== undefined) {
			tail += 1;
		}

		this.#head = head;
		this.#tail = tail;
		this.entries = entries;
	}

	#syncStatus(): void {
		this.status =
			this.#failed.size > 0 ? 'error' : this.#inFlight.size > 0 ? 'loading' : 'idle';
	}
}

/** Negative when [a] belongs above [b], in the order the server lists them. */
function compareMails(a: Mail, b: Mail): number {
	const sent = Date.parse(b.sent_at) - Date.parse(a.sent_at);
	if (sent !== 0) return sent;
	// Only breaks ties, and only has to match the server's `id` tiebreak, not mean anything.
	return a.id < b.id ? 1 : a.id > b.id ? -1 : 0;
}

/** Where [mail] goes among the loaded entries; holes carry no order and are stepped over. */
function insertionIndex(entries: (Mail | undefined)[], mail: Mail): number {
	for (let index = 0; index < entries.length; index += 1) {
		const known = entries[index];
		if (known !== undefined && compareMails(known, mail) > 0) return index;
	}

	return entries.length;
}

function shiftSecond(isoTimestamp: string, seconds: number): string {
	return new Date(Date.parse(isoTimestamp) + seconds * 1000).toISOString();
}
