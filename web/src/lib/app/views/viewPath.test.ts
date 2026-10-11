import {expect, test} from "bun:test";
import {accountView} from "./predefinedViews";
import {
    inlineViewHref,
    openInlineView,
    openViewId,
    parseInlineView,
    parseViewId,
    viewHref,
    viewSlug,
    viewUrl,
} from "./viewPath";

const id = "0198f3ac-6d21-7c4e-9b0f-2a1c7d5e8f30";
const bare = "0198f3ac6d217c4e9b0f2a1c7d5e8f30";

test("the name goes in front of the id, spaces as hyphens", () => {
    expect(viewSlug(id, "Neue Ansicht 2")).toBe(`Neue-Ansicht-2-${bare}`);
    expect(viewSlug(id)).toBe(bare);
    expect(viewSlug(id, "   ")).toBe(bare);
});

test("the id comes back out of a slug, whatever the name was", () => {
    expect(parseViewId(viewSlug(id, "Neue Ansicht 2"))).toBe(id);
    // A renamed view is the same view: the name in front is not read back.
    expect(parseViewId(viewSlug(id, "Etwas ganz anderes"))).toBe(id);
    expect(parseViewId("keine-ansicht")).toBeNull();
    expect(parseViewId(null)).toBeNull();
});

test("the url is the listing with the view in the query", () => {
    const url = viewUrl(id, "Neue Ansicht 2", new URL("http://localhost/settings"));

    expect(url.pathname).toBe("/");
    expect(url.searchParams.get("view")).toBe(`Neue-Ansicht-2-${bare}`);
});

test("opening a view closes the mail that was open beside the listing", () => {
    const listWithMail = new URL("http://localhost/?email=Termin-0b7f1d2c3e4a5b6c7d8e9f0a1b2c3d4e");
    const url = viewUrl(id, "Neue Ansicht 2", listWithMail);

    expect(url.searchParams.has("email")).toBe(false);
    expect(openViewId(url)).toBe(id);
});

test("the href carries no origin", () => {
    expect(viewHref(id, "Neue Ansicht 2", new URL("http://localhost/"))).toBe(
        `/?view=Neue-Ansicht-2-${bare}`
    );
});

test("a url without a view has none open", () => {
    expect(openViewId(new URL("http://localhost/"))).toBeNull();
    expect(openViewId(new URL("http://localhost/?view=beliebig"))).toBeNull();
});

test("the inbox is the bare listing, not a view in the query", () => {
    // It is what the listing shows when nothing is asked for, so it has no parameter of its own.
    expect(viewHref("inbox", null, new URL("http://localhost/?view=sent"))).toBe("/");
    expect(viewHref("sent", null, new URL("http://localhost/"))).toBe("/?view=sent");
});

test("a listing nothing names is spelled out in the url, and read back whole", () => {
    const settings = accountView(id);
    const url = new URL(inlineViewHref(settings, new URL("http://localhost/?email=x")), "http://localhost");

    expect(url.searchParams.has("email")).toBe(false);
    expect(openInlineView(url)).toEqual(settings);
    // It names no view: neither one the app brings nor a stored one.
    expect(openViewId(url)).toBeNull();
});

test("a name or a slug is not a listing spelled out", () => {
    expect(openInlineView(new URL("http://localhost/"))).toBeNull();
    expect(openInlineView(new URL("http://localhost/?view=sent"))).toBeNull();
    expect(openInlineView(viewUrl(id, "{Neue Ansicht}", new URL("http://localhost/")))).toBeNull();
});

test("what is not exactly a view is no view", () => {
    const sorting = {kind: "date", reversed: false};

    expect(parseInlineView("{")).toBeNull();
    expect(parseInlineView(JSON.stringify({groupings: [], filter: {}}))).toBeNull();
    expect(parseInlineView(JSON.stringify({groupings: [{kind: "colour"}], filter: {}, sorting}))).toBeNull();
    expect(parseInlineView(JSON.stringify({groupings: [], filter: {archivedState: ["Deleted"]}, sorting}))).toBeNull();
    expect(parseInlineView(JSON.stringify({groupings: [], filter: {hasLabels: "l-1"}, sorting}))).toBeNull();
    // Every filter left out is every filter not set.
    expect(parseInlineView(JSON.stringify({groupings: [], filter: {}, sorting}))?.filter).toEqual({
        readState: null,
        archivedState: null,
        imapAccountIds: null,
        sentBy: null,
        sentTo: null,
        hasLabels: null,
    });
});
