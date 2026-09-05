import {expect, test} from "bun:test";
import {morphsBetweenPanelAndPage} from "./mailViewTransition";

const url = (path: string) => new URL(path, "https://overmail.test");

const LIST_WITH_MAIL = url("/?email=Termin-0b7f1d2c3e4a5b6c7d8e9f0a1b2c3d4e");
const MAIL_PAGE = url("/emails/Termin-0b7f1d2c3e4a5b6c7d8e9f0a1b2c3d4e?from=mail-list");

test("the panel becoming the page and the page becoming the panel again", () => {
	expect(morphsBetweenPanelAndPage(LIST_WITH_MAIL, MAIL_PAGE)).toBe(true);
	expect(morphsBetweenPanelAndPage(MAIL_PAGE, LIST_WITH_MAIL)).toBe(true);
});

test("a mail page nobody was sent to from the list has nothing to morph with", () => {
	const opened = url("/emails/Termin-0b7f1d2c3e4a5b6c7d8e9f0a1b2c3d4e");

	expect(morphsBetweenPanelAndPage(LIST_WITH_MAIL, opened)).toBe(false);
	expect(morphsBetweenPanelAndPage(opened, LIST_WITH_MAIL)).toBe(false);
});

test("a list without an open mail has no panel to grow", () => {
	expect(morphsBetweenPanelAndPage(url("/"), MAIL_PAGE)).toBe(false);
});

test("everything else swaps the way it did", () => {
	expect(morphsBetweenPanelAndPage(LIST_WITH_MAIL, url("/my-stack"))).toBe(false);
	expect(morphsBetweenPanelAndPage(MAIL_PAGE, url("/my-stack"))).toBe(false);
	expect(morphsBetweenPanelAndPage(url("/my-stack"), MAIL_PAGE)).toBe(false);
});

test("a first load has nothing behind it", () => {
	expect(morphsBetweenPanelAndPage(null, MAIL_PAGE)).toBe(false);
	expect(morphsBetweenPanelAndPage(LIST_WITH_MAIL, undefined)).toBe(false);
});
