/** What the user decided to do with a mail. Tags sit on the mail itself, not on the decision. */
export type EmailClassification = {
	to: 'archive' | 'spam' | 'respond_later' | 'keep';
};

type Decision = EmailClassification['to'];

/**
 * One colour per decision: beige for the archive, red for spam, green for a deferred reply. The
 * key that makes the decision and the mail that carries it read from the same map, so a mail can
 * never fly out in a colour other than the key that sent it there.
 */
const ACCENTS: Record<Decision, string | null> = {
	archive: 'var(--color-amber-400)',
	spam: 'var(--color-red-500)',
	respond_later: 'var(--color-emerald-500)',
	// No decision of its own yet: keeping a mail is what happens anyway.
	keep: null
};

/** The wash laid over a mail that has left the stack. Transparent while it is still in it. */
export function classificationTint(decision: Decision | undefined): string {
	const accent = decision ? ACCENTS[decision] : null;
	return accent ? `color-mix(in oklab, ${accent} 18%, transparent)` : 'transparent';
}

/**
 * Hover and pressed colours for the key that triggers a decision. Opacity-based, so the same
 * classes hold up in light and dark mode.
 */
export const CLASSIFICATION_KEY_CLASSES: Record<Decision, string> = {
	archive: 'hover:border-amber-400 hover:bg-amber-400/20 data-pressed:border-amber-400 data-pressed:bg-amber-400/35',
	spam: 'hover:border-red-500 hover:bg-red-500/20 data-pressed:border-red-500 data-pressed:bg-red-500/35',
	respond_later:
		'hover:border-emerald-500 hover:bg-emerald-500/20 data-pressed:border-emerald-500 data-pressed:bg-emerald-500/35',
	keep: ''
};
