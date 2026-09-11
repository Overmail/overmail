import {expect, test} from "bun:test";
import {openViewId, parseViewId, viewHref, viewSlug, viewUrl} from "./viewPath";

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
