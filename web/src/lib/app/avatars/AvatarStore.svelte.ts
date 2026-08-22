import { browser } from '$app/environment';
import {
	avatarRepository,
	type AvatarRefresh,
	type AvatarRepository
} from '$lib/repository/AvatarRepository';

/** How often the list is read again while a refresh is going, so pictures appear as they land. */
const POLL_INTERVAL_MS = 2000;

/**
 * The pictures of the people in the mailbox, by address.
 *
 * Read once and used by every row: a mailbox is filled by a handful of senders, so the mails
 * themselves carry no avatar at all -- a listing would repeat the same ids thousands of times, and
 * every refresh would mean reloading the whole list to see them.
 *
 * Addresses nothing was found for are simply absent, which is what [urlFor] answers null for. That
 * is deliberate: a row with no picture renders its initials without ever firing a request that
 * could only come back 404.
 */
class AvatarStore {
	/** Addresses in the mailbox altogether, whether a picture was found for them or not. */
	addressesTotal = $state(0);
	/** The last refresh as the server reports it, null while none was ever started. */
	refresh = $state<AvatarRefresh | null>(null);
	/** Whether the first read has come back. Before that there is nothing to say about a row. */
	loaded = $state(false);
	failed = $state(false);

	/** Avatar id by lowercased address. */
	#byAddress = $state.raw<Record<string, string>>({});

	readonly #repository: AvatarRepository;
	/** So a table full of avatars does not each ask for the same list. */
	#requested = false;
	#reading = false;
	#pollTimer: ReturnType<typeof setTimeout> | null = null;

	constructor(repository: AvatarRepository = avatarRepository) {
		this.#repository = repository;
	}

	/** Whether a refresh is going right now, which is when this list keeps changing. */
	get isRefreshing(): boolean {
		return this.refresh?.running === true;
	}

	/** Addresses a picture is held for. */
	get coveredCount(): number {
		return Object.keys(this.#byAddress).length;
	}

	/**
	 * Reads the list unless it has been read already. Cheap to call from anything that shows an
	 * avatar, which is how a table gets the pictures without knowing where they come from.
	 */
	ensureLoaded(): void {
		if (this.#requested) return;
		this.#requested = true;
		void this.reload();
	}

	/** The url of the picture for an address, or null when none is held for it. */
	urlFor(address: string): string | null {
		const id = this.#byAddress[address.toLowerCase()];
		return id === undefined ? null : this.#repository.url(id);
	}

	/**
	 * Starts a download run and follows it until it is over, so the pictures show up on their own.
	 *
	 * @param all every address, throwing away what is cached first. Without it only the addresses
	 *   no picture was ever found for are visited.
	 */
	async startRefresh(all: boolean): Promise<void> {
		this.failed = false;
		try {
			const started = await this.#repository.refresh(all);
			// Null means one is already going; the poll below picks that one up either way.
			if (started) this.refresh = started;
			this.#poll();
		} catch {
			this.failed = true;
		}
	}

	/** Reads the list again. What the polling calls, and what a failed read is retried with. */
	async reload(): Promise<void> {
		// Nothing to fetch while server-rendering, and this instance is shared per process there.
		if (!browser) return;
		if (this.#reading) return;

		this.#reading = true;
		try {
			const list = await this.#repository.list();

			this.#byAddress = Object.fromEntries(
				list.avatars.map((avatar) => [avatar.address.toLowerCase(), avatar.id])
			);
			this.addressesTotal = list.addresses_total;
			this.refresh = list.refresh ?? null;
			this.loaded = true;
			this.failed = false;

			// A run someone else started, or one still going after a reload of the page.
			if (this.isRefreshing) this.#poll();
		} catch {
			this.failed = true;
		} finally {
			this.#reading = false;
		}
	}

	/** Schedules the next read while a run is going. At most one timer is ever outstanding. */
	#poll(): void {
		if (!browser) return;
		if (this.#pollTimer !== null) return;

		this.#pollTimer = setTimeout(() => {
			this.#pollTimer = null;
			void this.reload();
		}, POLL_INTERVAL_MS);
	}
}

/**
 * The one instance. Module level, like the view preferences: an avatar sits in a table cell that
 * is rendered by the table itself, so there is no place to hand it down from. Nothing is fetched
 * outside the browser, so the instance a server-rendered request sees stays empty.
 */
export const avatarStore = new AvatarStore();
