const API = '/api/avatars';

/** The picture found for one address. */
export type Avatar = {
	address: string;
	/** Also the cache key of the url the bytes sit behind. */
	id: string;
	/** Which resolver found it, e.g. `bimi`. */
	source: string;
	/** ISO-8601. */
	created_at: string;
};

/** How far a refresh got. */
export type AvatarRefresh = {
	/** False means these are the final numbers of that run rather than a snapshot of it. */
	running: boolean;
	/** Whether it visits the whole address book rather than only the addresses without a picture. */
	all: boolean;
	total: number;
	done: number;
	/** Addresses a picture was found for, out of the ones visited so far. */
	found: number;
};

/** The avatar cache as the server reports it. */
export type AvatarList = {
	/** One entry per address a picture was found for; the others are simply absent. */
	avatars: Avatar[];
	/** Addresses in the caller's book altogether, so the covered share can be shown. */
	addresses_total: number;
	/** The last refresh, absent when none was ever started. */
	refresh?: AvatarRefresh | null;
};

/**
 * The avatar cache. Nothing else builds an avatar url or asks for a refresh.
 *
 * There is no cache to keep in sync here: the avatar id is part of the url, so a picture that gets
 * replaced is a different url and the browser cannot hand back an old one.
 */
export class AvatarRepository {
	/** Every picture the caller's address book points at, by address. */
	async list(): Promise<AvatarList> {
		const response = await fetch(API, { credentials: 'include' });
		if (!response.ok) throw new Error(`Could not list avatars: ${response.status}`);
		return (await response.json()) as AvatarList;
	}

	/** The url the bytes of one avatar sit behind. Safe to cache hard, see the class comment. */
	url(id: string): string {
		return `${API}/${id}`;
	}

	/**
	 * Asks the server to download avatars.
	 *
	 * Comes back once the work has been accepted, not once it is done -- a real address book takes
	 * minutes. Watch [list] for how far it got and for the pictures as they land.
	 *
	 * @param all every address, throwing away what is cached for them first. Without it only the
	 *   addresses no picture was ever found for are visited.
	 * @returns null when a refresh is already running, which is when the server declines.
	 */
	async refresh(all: boolean): Promise<AvatarRefresh | null> {
		const response = await fetch(`${API}/refresh?all=${all}`, {
			method: 'POST',
			credentials: 'include'
		});

		// 409: one is already going, and its progress is what the caller wanted to know anyway.
		if (response.status === 409) return null;
		if (!response.ok) throw new Error(`Could not refresh avatars: ${response.status}`);

		return (await response.json()) as AvatarRefresh;
	}
}

export const avatarRepository = new AvatarRepository();
