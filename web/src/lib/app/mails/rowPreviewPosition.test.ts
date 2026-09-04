import {expect, test} from "bun:test";
import {PREVIEW_GAP, PREVIEW_MARGIN, previewPosition, type PreviewAnchor} from "./rowPreviewPosition";

/** A row halfway down a 1000x800 window, with a 320x260 card. */
const anchor = (over: Partial<PreviewAnchor> = {}): PreviewAnchor => ({
    cursorX: 500,
    rowTop: 400,
    rowBottom: 440,
    cardWidth: 320,
    cardHeight: 260,
    viewportWidth: 1000,
    viewportHeight: 800,
    ...over,
});

test("centred on the cursor, below the row", () => {
    expect(previewPosition(anchor())).toEqual({
        left: 500 - 160,
        top: 440 + PREVIEW_GAP,
        placement: "below",
    });
});

test("the card stops at the edges instead of following the cursor", () => {
    expect(previewPosition(anchor({cursorX: 20})).left).toBe(PREVIEW_MARGIN);
    expect(previewPosition(anchor({cursorX: 990})).left).toBe(1000 - 320 - PREVIEW_MARGIN);
    // Narrower than the card: cut off at the far edge, not at the near one.
    expect(previewPosition(anchor({cursorX: 100, viewportWidth: 200})).left).toBe(PREVIEW_MARGIN);
});

test("above the row where the space below has run out", () => {
    // 260 of card does not fit under a row that ends at 600 in an 800 tall window.
    expect(previewPosition(anchor({rowTop: 560, rowBottom: 600}))).toEqual({
        left: 340,
        top: 560 - PREVIEW_GAP - 260,
        placement: "above",
    });
});

test("the last row that still fits below stays below", () => {
    // Exactly the height the window has left for it.
    const rowBottom = 800 - PREVIEW_MARGIN - PREVIEW_GAP - 260;
    expect(previewPosition(anchor({rowTop: rowBottom - 40, rowBottom})).placement).toBe("below");
    expect(previewPosition(anchor({rowTop: rowBottom - 39, rowBottom: rowBottom + 1})).placement)
        .toBe("above");
});

test("a card too tall for either side covers the row rather than the window's edge", () => {
    expect(previewPosition(anchor({cardHeight: 900}))).toEqual({
        left: 340,
        top: PREVIEW_MARGIN,
        placement: "above",
    });
});

test("an unmeasured card is placed below, where it will be once it has a height", () => {
    expect(previewPosition(anchor({cardHeight: 0})).placement).toBe("below");
});
