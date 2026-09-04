import {expect, test} from "bun:test";
import {emailPath, emailSlug, parseEmailId} from "./emailPath";

const id = "0198f3ac-6d21-7c4e-9b0f-2a1c7d5e8f30";
const bare = "0198f3ac6d217c4e9b0f2a1c7d5e8f30";

test("the id alone for a mail nothing knows the subject of", () => {
    expect(emailPath(id)).toBe(`/email/${bare}`);
    expect(emailPath(id, null)).toBe(`/email/${bare}`);
    // A mail without a subject is one we know, and still has nothing to put in front.
    expect(emailPath(id, "")).toBe(`/email/${bare}`);
    expect(emailPath(id, "   ")).toBe(`/email/${bare}`);
});

test("the subject goes in front, spaces as hyphens", () => {
    expect(emailPath(id, "Termin Donnerstag")).toBe(`/email/Termin-Donnerstag-${bare}`);
    // Runs of whitespace are one hyphen, and none is left at either end.
    expect(emailPath(id, "  Deine   Bestellung\nist unterwegs  ")).toBe(
        `/email/Deine-Bestellung-ist-unterwegs-${bare}`
    );
});

test("64 characters of it at most", () => {
    const subject = "A".repeat(70);
    expect(emailPath(id, subject)).toBe(`/email/${"A".repeat(64)}-${bare}`);
});

test("what is left of the subject is url encoded", () => {
    expect(emailPath(id, "Rückfrage 50% ?")).toBe(`/email/R%C3%BCckfrage-50%25-%3F-${bare}`);
    // A slash of its own would be another path segment.
    expect(emailPath(id, "Re: a/b")).toBe(`/email/Re%3A-a%2Fb-${bare}`);
});

test("a subject cut mid emoji is still a url", () => {
    // The 64th and 65th code unit are the halves of one code point; slicing by code unit would
    // leave a lone surrogate, which cannot be encoded at all.
    const subject = `${"a".repeat(63)}🙂🙂`;
    expect(emailPath(id, subject)).toBe(`/email/${"a".repeat(63)}%F0%9F%99%82-${bare}`);
});

test("the query form of the same slug is left unencoded", () => {
    // What goes into a `?email=` parameter: URLSearchParams encodes it, and encoding it here as
    // well would put %25 in the url for every % of the subject.
    expect(emailSlug(id)).toBe(bare);
    expect(emailSlug(id, "Termin Donnerstag")).toBe(`Termin-Donnerstag-${bare}`);
    expect(emailSlug(id, "Rückfrage 50% ?")).toBe(`Rückfrage-50%-?-${bare}`);
});

test("the id comes back out of either form", () => {
    expect(parseEmailId(emailSlug(id, "Termin Donnerstag"))).toBe(id);
    expect(parseEmailId(emailSlug(id, "Rückfrage 50% ?"))).toBe(id);
    expect(parseEmailId(emailPath(id, "Re: a/b").replace("/email/", ""))).toBe(id);
    expect(parseEmailId(bare.toUpperCase())).toBe(id);
});

test("nothing that is not a mail", () => {
    expect(parseEmailId(null)).toBeNull();
    expect(parseEmailId("")).toBeNull();
    expect(parseEmailId("beliebiger-text")).toBeNull();
    // 31 digits, and 32 that are not all hex.
    expect(parseEmailId(bare.slice(1))).toBeNull();
    expect(parseEmailId(`z${bare.slice(1)}`)).toBeNull();
});
