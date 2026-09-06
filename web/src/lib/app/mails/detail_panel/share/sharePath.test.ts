import {expect, test} from "bun:test";
import {parseShareId, sharePath, shareUrl, subjectFor} from "./sharePath";
import type {Share} from "$lib/repository/ShareRepository";

const ID = "11111111-2222-3333-4444-555555555555";
const BARE = "11111111222233334444555555555555";

const SHARE: Share = {
	id: ID,
	shareName: null,
	sharedAt: 1772000000,
	validUntil: null,
	includeLabels: false,
	hasPassword: false,
	allowMetadataWithoutPassword: false
};

test("a share is spelled without the hyphens of its id, like a mail is", () => {
	expect(sharePath(ID)).toBe(`/share/${BARE}`);
	expect(sharePath(ID, null)).toBe(`/share/${BARE}`);
	expect(sharePath(ID, "  ")).toBe(`/share/${BARE}`);
});

test("the subject is in front of the id, encoded, like on a mail page", () => {
	expect(sharePath(ID, "Termin Donnerstag")).toBe(`/share/Termin-Donnerstag-${BARE}`);
	expect(sharePath(ID, "Re: a/b")).toBe(`/share/Re%3A-a%2Fb-${BARE}`);
});

test("the copied link is absolute, because it is pasted outside this app", () => {
	expect(shareUrl(ID, "https://overmail.example", "Termin")).toBe(
		`https://overmail.example/share/Termin-${BARE}`
	);
});

test("a link that opens without a password carries the subject", () => {
	expect(subjectFor(SHARE, "Termin Donnerstag")).toBe("Termin Donnerstag");
	expect(subjectFor({...SHARE, hasPassword: true, allowMetadataWithoutPassword: true}, "Termin")).toBe("Termin");
});

test("a link that hides everything behind its password carries no subject either", () => {
	// The url is read by everything the link passes through, and none of that types the password.
	expect(subjectFor({...SHARE, hasPassword: true}, "Gehaltsabrechnung")).toBeNull();
	expect(sharePath(ID, subjectFor({...SHARE, hasPassword: true}, "Gehaltsabrechnung"))).toBe(`/share/${BARE}`);
});

test("a mail without a subject is the bare id, password or not", () => {
	expect(subjectFor(SHARE, null)).toBeNull();
	expect(subjectFor(SHARE, undefined)).toBeNull();
});

test("the id is read back out of a link, whatever subject was put in front of it", () => {
	expect(parseShareId(`Termin-Donnerstag-${BARE}`)).toBe(ID);
	expect(parseShareId(BARE)).toBe(ID);
	expect(parseShareId("nothing-here")).toBeNull();
	expect(parseShareId(null)).toBeNull();
});
