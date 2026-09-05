import {expect, test} from "bun:test";
import type {EmailParticipant} from "$lib/repository/EmailRepository.svelte";
import {displayName, isSelf, spelledOut} from "./participants";

const person = (name: string | null, address: string): EmailParticipant => ({
    id: address,
    name,
    address,
    avatarUrl: null,
    avatarPadding: null,
});

test("a correspondent is their name, or their address where the mail carried none", () => {
    expect(displayName(person("Julius Babies", "julius@example.com"))).toBe("Julius Babies");
    expect(displayName(person(null, "julius@example.com"))).toBe("julius@example.com");
});

test("spelled out, both -- and no empty brackets for a bare address", () => {
    expect(spelledOut(person("Julius Babies", "julius@example.com")))
        .toBe("Julius Babies (julius@example.com)");
    expect(spelledOut(person(null, "julius@example.com"))).toBe("julius@example.com");
});


test("the reader is recognised by any address they receive mail under", () => {
    const me = {addresses: ["julius@example.com", "julius@work.example"]};

    expect(isSelf(person(null, "julius@example.com"), me)).toBe(true);
    // Not only the account's own address: a mail account of theirs is theirs as well.
    expect(isSelf(person("Julius", "julius@work.example"), me)).toBe(true);
    // A sender that wrote the address differently is still the same mailbox.
    expect(isSelf(person("Julius", "JULIUS@Example.com"), me)).toBe(true);
    expect(isSelf(person(null, "max@example.com"), me)).toBe(false);

    // Before the account is read there is nobody to be: everybody is named until it arrives.
    expect(isSelf(person(null, "julius@example.com"), null)).toBe(false);
});
