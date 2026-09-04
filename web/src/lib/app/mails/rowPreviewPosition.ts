/** Between the row and the card, and between the card and the edge of the window. */
export const PREVIEW_GAP = 12;
export const PREVIEW_MARGIN = 8;

/** Everything the card's place depends on, in client coordinates. */
export type PreviewAnchor = {
	/** Where the cursor is on the x axis; the card is centred on it. */
	cursorX: number;
	/** The row the card belongs to. */
	rowTop: number;
	rowBottom: number;
	cardWidth: number;
	/** Zero until the card has been laid out once, which reads as "fits below". */
	cardHeight: number;
	viewportWidth: number;
	viewportHeight: number;
};

/** Which side of the row the card ended up on; what it grows out of when it appears. */
export type PreviewPlacement = "below" | "above";

/**
 * Where the preview card goes for a row under the cursor: centred on the cursor, below the row,
 * and inside the window.
 *
 * The card is what gives way, never the cursor -- at the edges of the window it stops instead of
 * following, and where the space below the row has run out it flips above it.
 */
export function previewPosition(anchor: PreviewAnchor): {
	left: number;
	top: number;
	placement: PreviewPlacement;
} {
	const {cursorX, rowTop, rowBottom, cardWidth, cardHeight, viewportWidth, viewportHeight} =
		anchor;

	// The two bounds can cross on a window narrower than the card; the left one wins, so the card
	// is cut off at the far edge rather than at the near one.
	const left = Math.min(
		Math.max(cursorX - cardWidth / 2, PREVIEW_MARGIN),
		Math.max(viewportWidth - cardWidth - PREVIEW_MARGIN, PREVIEW_MARGIN)
	);

	const below = rowBottom + PREVIEW_GAP;
	if (below + cardHeight <= viewportHeight - PREVIEW_MARGIN) {
		return {left, top: below, placement: "below"};
	}

	// Above the row, and no further up than the window goes: a card taller than the space either
	// way covers the row it belongs to, which beats hanging off the screen.
	return {
		left,
		top: Math.max(rowTop - PREVIEW_GAP - cardHeight, PREVIEW_MARGIN),
		placement: "above",
	};
}
