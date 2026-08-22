import {
	MAX_IDS_PER_REQUEST,
	mailRepository,
	type Mail,
	type MailRepository
} from '$lib/repository/MailRepository';
import { threadRepository, type ThreadOverview, type ThreadRepository } from '$lib/repository/ThreadRepository';
import { MailStore } from './MailStore.svelte';

/** What sits at one index of the grouped list. */
export type ThreadedSlot =
	| { kind: 'group'; thread: ThreadOverview | null }
	/** A mail of a thread. Its id is known before the mail is, which is the whole point. */
	| { kind: 'mail'; id: string }
	/** A mail in no thread, at [index] of the unfiled stretch. Its id is not known yet. */
	| { kind: 'unfiled'; index: number };

/**
 * The mailbox arranged by thread.
 *
 * Threads cannot be paged the way the flat list is: a thread's mails sit wherever they were sent,
 * so a matter opened in 2019 and answered last week has one mail near the top of the mailbox and
 * the rest thousands of rows down -- and a list built from whatever mails a walk has reached would
 * only ever show the threads it happened to stumble on.
 *
 * So the skeleton is read first and whole: every thread with the ids of its mails, which is small
 * enough to fetch in one go and is the entire layout. From it every index of the list is known
 * before anything is loaded, and what is on screen is asked for by id.
 *
 * The mails in no thread are the one part that is still a stretch of the mailbox, so they are
 * exactly that: a [MailStore] of its own, narrowed to the unfiled mails and paged like any list.
 */
export class ThreadedMailStore {
	/** Every thread, newest mail first. Empty until the skeleton is here. */
	threads = $state.raw<ThreadOverview[]>([]);
	/** Whether the skeleton has been read. Nothing about the layout is known before that. */
	initialized = $state(false);
	/** True while the skeleton is being read; the unfiled stretch reports its own. */
	isLoading = $state(false);
	failed = $state(false);

	/** The mails that have been fetched by id, by id. */
	mails = $state.raw<Record<string, Mail>>({});

	/** The mails in no thread -- a list in its own right, paged like the flat one. */
	readonly unfiled: MailStore;

	readonly #mailRepository: MailRepository;
	readonly #threadRepository: ThreadRepository;
	/** Ids asked for, so a scroll does not ask for the same mail once per frame. */
	#requested = new Set<string>();
	#requestedSkeleton = false;

	constructor(
		mails: MailRepository = mailRepository,
		threads: ThreadRepository = threadRepository
	) {
		this.#mailRepository = mails;
		this.#threadRepository = threads;
		this.unfiled = new MailStore(mails, undefined, { filed: false });
	}

	/**
	 * What sits at each index, in order: every thread as a header followed by its mails, then the
	 * mails in no thread under one last header.
	 *
	 * Materialised rather than worked out per index: it is one small object per row and it only
	 * changes when the skeleton does or when the unfiled stretch first reports its length, whereas
	 * a lookup would run for every row of every frame.
	 */
	layout: ThreadedSlot[] = $derived.by(() => {
		const slots: ThreadedSlot[] = [];

		for (const thread of this.threads) {
			slots.push({ kind: 'group', thread });
			for (const id of thread.mail_ids) slots.push({ kind: 'mail', id });
		}

		if (this.unfiled.total > 0) {
			slots.push({ kind: 'group', thread: null });
			for (let index = 0; index < this.unfiled.total; index += 1) {
				slots.push({ kind: 'unfiled', index });
			}
		}

		return slots;
	});

	get rowCount(): number {
		return this.layout.length;
	}

	/** The mail at an index of the list, or undefined while it is not loaded. */
	mailAt(index: number): Mail | undefined {
		const slot = this.layout[index];
		if (slot === undefined) return undefined;
		if (slot.kind === 'mail') return this.mails[slot.id];
		if (slot.kind === 'unfiled') return this.unfiled.entries[slot.index];
		return undefined;
	}

	/** The row id at an index -- a group's own, or the mail's, or undefined while unknown. */
	rowIdAt(index: number): string | undefined {
		const slot = this.layout[index];
		if (slot === undefined) return undefined;
		if (slot.kind === 'group') return slot.thread ? `thread-${slot.thread.id}` : 'thread-none';
		if (slot.kind === 'mail') return slot.id;
		return this.unfiled.entries[slot.index]?.id;
	}

	/**
	 * Makes sure the rows between [from] and [to] are on their way. Cheap to call on every scroll
	 * frame: a mail already here or already asked for is not asked for again.
	 */
	ensureRange(from: number, to: number): void {
		this.#loadSkeleton();
		// The unfiled stretch reports its length with its first page, and the layout needs that
		// length before it can put anything after the threads.
		this.unfiled.ensureRange(0, 0);

		if (!this.initialized) return;

		const first = Math.max(0, Math.min(from, to));
		const last = Math.min(this.layout.length - 1, Math.max(from, to));
		if (first > last) return;

		const wanted: string[] = [];
		let unfiledFrom = -1;
		let unfiledTo = -1;

		for (let index = first; index <= last; index += 1) {
			const slot = this.layout[index];
			if (slot.kind === 'mail') {
				if (this.mails[slot.id] === undefined && !this.#requested.has(slot.id)) {
					wanted.push(slot.id);
				}
			} else if (slot.kind === 'unfiled') {
				if (unfiledFrom < 0) unfiledFrom = slot.index;
				unfiledTo = slot.index;
			}
		}

		// The unfiled mails are a stretch of the mailbox like any other, so their own store pages
		// them -- by position, from whichever end is nearer.
		if (unfiledFrom >= 0) this.unfiled.ensureRange(unfiledFrom, unfiledTo);

		for (let at = 0; at < wanted.length; at += MAX_IDS_PER_REQUEST) {
			void this.#loadIds(wanted.slice(at, at + MAX_IDS_PER_REQUEST));
		}
	}

	/** Asks again for the skeleton after a failure. */
	retry(): void {
		this.#requestedSkeleton = false;
		this.failed = false;
		this.#loadSkeleton();
		this.unfiled.retry();
	}

	#loadSkeleton(): void {
		if (this.#requestedSkeleton) return;
		this.#requestedSkeleton = true;
		void this.#readSkeleton();
	}

	async #readSkeleton(): Promise<void> {
		this.isLoading = true;

		try {
			this.threads = await this.#threadRepository.listThreads();
			this.initialized = true;
			this.failed = false;
		} catch {
			// Asked again by `retry`; without a skeleton there is no layout and no list.
			this.#requestedSkeleton = false;
			this.failed = true;
		} finally {
			this.isLoading = false;
		}
	}

	async #loadIds(ids: string[]): Promise<void> {
		for (const id of ids) this.#requested.add(id);

		try {
			const page = await this.#mailRepository.listMails({ ids, limit: ids.length });

			const next = { ...this.mails };
			for (const mail of page.mails) next[mail.id] = mail;
			this.mails = next;
		} catch {
			// Asked again the next time the rows come past.
			for (const id of ids) this.#requested.delete(id);
		}
	}
}
