import { browser } from '$app/environment';

/** Chrome-only event that carries the deferred install prompt. Not in lib.dom. */
type BeforeInstallPromptEvent = Event & {
	prompt: () => Promise<void>;
	userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
};

/**
 * Chrome fires `beforeinstallprompt` once, very early — often before the first
 * component mounts. The listener therefore lives at module scope, which runs
 * during client bootstrap, and the event is kept until someone asks for it.
 */
let deferred = $state<BeforeInstallPromptEvent | null>(null);
let installed = $state(false);

if (browser) {
	installed = window.matchMedia('(display-mode: standalone)').matches;

	window.addEventListener('beforeinstallprompt', (event) => {
		// Suppresses Chrome's own mini-infobar so the in-app button is the only affordance.
		event.preventDefault();
		deferred = event as BeforeInstallPromptEvent;
	});

	window.addEventListener('appinstalled', () => {
		installed = true;
		deferred = null;
	});
}

export const install = {
	/** True once Chrome has told us the install criteria are met. */
	get available() {
		return deferred !== null;
	},
	/** True when we are already running as an installed app. */
	get installed() {
		return installed;
	},
	/**
	 * Shows the browser's install dialog. The event is single-use, so it is dropped
	 * afterwards no matter what the user picked — Chrome hands us a fresh one if the
	 * app is still installable.
	 */
	async prompt() {
		const event = deferred;
		if (!event) return null;
		await event.prompt();
		const { outcome } = await event.userChoice;
		deferred = null;
		return outcome;
	}
};
