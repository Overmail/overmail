import { browser } from '$app/environment';

const STORAGE_KEY = 'overmail.mails.group-by-thread';

/**
 * Whether the list puts the mails of a thread together.
 *
 * A view preference, not server data: it never leaves the browser, so it lives in localStorage
 * rather than behind a repository.
 */
class MailGrouping {
	byThread = $state(browser && localStorage.getItem(STORAGE_KEY) === 'true');

	toggle(): void {
		this.byThread = !this.byThread;
		if (browser) localStorage.setItem(STORAGE_KEY, String(this.byThread));
	}
}

export const mailGrouping = new MailGrouping();
