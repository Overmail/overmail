import {expect, test} from "bun:test";
import {attributeOf} from "./toolCallAttributes";

test("reads an attribute whatever case it is written in", () => {
    const attributes = {emailId: "abc", avatarurl: "/api/avatars/1"};

    expect(attributeOf(attributes, "emailId")).toBe("abc");
    expect(attributeOf(attributes, "avatarUrl")).toBe("/api/avatars/1");
});

test("decodes the entities the server escaped", () => {
    const attributes = {subject: "Re: &quot;5 &lt; 6&quot; &amp; more"};

    expect(attributeOf(attributes, "subject")).toBe('Re: "5 < 6" & more');
});

test("an escaped entity survives decoding", () => {
    // The mail's subject contained the text "&lt;", not a character.
    expect(attributeOf({subject: "&amp;lt;"}, "subject")).toBe("&lt;");
});

test("missing attributes and elements without any are null", () => {
    expect(attributeOf({emailId: "abc"}, "subject")).toBeNull();
    expect(attributeOf(undefined, "emailId")).toBeNull();
});
