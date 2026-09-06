import {expect, test} from "bun:test";
import {sharePath, shareUrl} from "./sharePath";

test("a share is spelled without the hyphens of its id, like a mail is", () => {
	expect(sharePath("11111111-2222-3333-4444-555555555555")).toBe("/share/11111111222233334444555555555555");
});

test("the copied link is absolute, because it is pasted outside this app", () => {
	expect(shareUrl("11111111-2222-3333-4444-555555555555", "https://overmail.example")).toBe(
		"https://overmail.example/share/11111111222233334444555555555555"
	);
});
