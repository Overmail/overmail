import { browser } from '$app/environment';

/**
 * A piece of ui state that survives a reload but not a new tab -- sessionStorage rather than
 * localStorage, so two windows of the app can sit on different chats and different widths.
 *
 * The stored value is read in the constructor, where on the server there is nothing to read and
 * the fallback stands. Anything backed by one therefore has to be rendered on the client only, or
 * the first client render would differ from what the server sent.
 */
export class SessionValue<T> {
	private readonly key: string;
	private value: T = $state()!;

	/**
	 * [revive] decides whether what is in the storage is still a value of this type -- the user
	 * can edit it, and an older version of the app may have written something else under the same
	 * key. Returning null falls back.
	 */
	constructor(key: string, fallback: T, revive: (stored: unknown) => T | null) {
		this.key = key;
		this.value = read(key, revive) ?? fallback;
	}

	get current(): T {
		return this.value;
	}

	set current(next: T) {
		this.value = next;
		write(this.key, next);
	}
}

function read<T>(key: string, revive: (stored: unknown) => T | null): T | null {
	if (!browser) return null;

	try {
		const stored = sessionStorage.getItem(key);
		return stored === null ? null : revive(JSON.parse(stored));
	} catch {
		// Unparseable, or storage denied outright as it is in some private windows. Either way
		// there is nothing to restore, and the fallback is a working answer.
		return null;
	}
}

function write(key: string, value: unknown): void {
	if (!browser) return;

	try {
		sessionStorage.setItem(key, JSON.stringify(value));
	} catch {
		// Denied or full. The value is still correct in memory for this page, and losing a panel
		// width across a reload is not worth telling the user about.
	}
}
