/**
 * The one step in this app that is animated by the browser rather than by Svelte: the mail beside
 * the list and the mail on its own page are the same mail, so the panel grows into the page
 * instead of one thing leaving and another arriving.
 *
 * Two halves. This file says *when* -- the layout starts a view transition only for the two
 * navigations named here, because a view transition freezes the whole document for its length and
 * that is only worth it where two views share something. And it says *what* moves: the names
 * below are put on the box and on the subject at both ends, and a name that is on one element
 * before the navigation and on another after it is what the browser morphs.
 *
 * A name must be unique per document, so the two ends must never be mounted at once -- not even
 * for the length of an outro. [startMorph] marks the document while the transition runs, and the
 * panel reads the mark to leave without its slide; see [isMorphing].
 */

import {EMAIL_PARAM, FROM_MAIL_LIST, FROM_PARAM} from "./emailPath";

/** The box: the panel at one end, the page's column at the other. */
export const MAIL_BOX_TRANSITION = "mail-box";

/** The subject: in the panel above the mail, on the page the heading next to the tools. */
export const MAIL_SUBJECT_TRANSITION = "mail-subject";

/** Everything the mail is -- Detail, the one component both ends render -- so it slides as one. */
export const MAIL_DETAIL_TRANSITION = "mail-detail";

/** The bar of tools -- Head at both ends -- anchored to the end edge, where its buttons are. */
export const MAIL_TOOLS_TRANSITION = "mail-tools";

/** The mail's own page, opened out of the listing -- the query the table and Head put on it. */
const isMailPageFromList = (url: URL) =>
	url.pathname.startsWith("/emails/") && url.searchParams.get(FROM_PARAM) === FROM_MAIL_LIST;

/** The listing with a mail open beside it, which is the only state the page can grow out of. */
const isListWithMailOpen = (url: URL) => url.pathname === "/" && url.searchParams.has(EMAIL_PARAM);

/**
 * Whether this navigation is that step, in either direction: out of the panel into the page, and
 * back out of the page into the list.
 *
 * Both ends are read off the urls alone, so the back button, the browser's own gesture and the
 * two buttons all go through the same door. Anything else -- a mail opened from a link, a step
 * within the list -- is false and swaps the way it always did.
 */
export function morphsBetweenPanelAndPage(
	from: URL | null | undefined,
	to: URL | null | undefined
): boolean {
	if (!from || !to) return false;

	return (
		(isListWithMailOpen(from) && isMailPageFromList(to)) ||
		(isMailPageFromList(from) && isListWithMailOpen(to))
	);
}

/**
 * On `<html>` from the moment a morph is started until the browser is done with it. What the
 * panel reads to leave without its slide: an outro would keep it in the DOM, with its name, while
 * the page is already there carrying the same name -- and two elements of one name is not
 * something the browser morphs, it drops the transition and throws.
 */
export const MORPHING_CLASS = "mail-morphing";

export const isMorphing = () =>
	typeof document !== "undefined" && document.documentElement.classList.contains(MORPHING_CLASS);

type ViewTransitionDocument = Document & {
	startViewTransition?: (update: () => Promise<void>) => ViewTransition;
};

/**
 * Runs [update] inside a view transition, with the document marked for its length. Null where
 * the browser has no view transitions; the caller then does what it would have done inside.
 *
 * The one place `startViewTransition` is called from -- the layout for a navigation, the harness
 * for a flip of its state -- so both have the same two snapshots and the same mark.
 */
export function startMorph(update: () => Promise<void>): ViewTransition | null {
	const start = (document as ViewTransitionDocument).startViewTransition;
	if (start === undefined) return null;

	document.documentElement.classList.add(MORPHING_CLASS);
	const transition = start.call(document, update);
	void transition.finished.finally(() => document.documentElement.classList.remove(MORPHING_CLASS));

	return transition;
}
