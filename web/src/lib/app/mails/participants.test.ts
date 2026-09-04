import {expect, test} from "bun:test";
import type {EmailParticipant} from "$lib/repository/EmailRepository.svelte";
import {displayName, spelledOut} from "./participants";

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

