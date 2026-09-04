/**
 * How far from the end of a scroll box still counts as being at it.
 *
 * A couple of pixels are unavoidable -- the three numbers below are fractional, and a browser
 * rounds them differently while zoomed -- and a reader who stops a hair short of the end still
 * means the end.
 */
export const AT_BOTTOM_TOLERANCE = 24;

/** What a scroll box says about itself; a real element is one, and so is a plain object. */
export type ScrollPosition = {
	scrollTop: number;
	scrollHeight: number;
	clientHeight: number;
};

/**
 * Whether the box is at its end. A box with nothing to scroll is: there is no end to be away
 * from, which is what a conversation that fits on screen looks like.
 */
export function isAtBottom(box: ScrollPosition, tolerance: number = AT_BOTTOM_TOLERANCE): boolean {
	return box.scrollHeight - box.scrollTop - box.clientHeight <= tolerance;
}
