import {expect, mock, test} from "bun:test";
import {
    ShareExpiredError,
    ShareNotFoundError,
    SharedEmailRepository,
    WrongSharePasswordError,
} from "./SharedEmailRepository";

function answering(status: number, body: unknown = null) {
    const fetcher = mock(async () => new Response(body === null ? "" : JSON.stringify(body), {status}));
    globalThis.fetch = fetcher as unknown as typeof fetch;
    return fetcher;
}

const SHARE = "s-1";

const OPEN = {
    needs_password: false,
    shared_by: {firstname: "Julius", lastname: "Babies"},
    metadata: {
        subject: "Die Rechnung",
        sender_name: "The Sender",
        sender_address: "sender@example.com",
        sent: 1772000000,
        labels: [{name: "Rechnungen", color: "#eeeeff"}],
    },
    content: {text: "Hallo", html: "<p>Hallo</p>"},
};

test("reads what the link shows", async () => {
    const fetcher = answering(200, OPEN);

    expect(await new SharedEmailRepository().read(SHARE)).toEqual({
        needsPassword: false,
        sharedBy: {firstname: "Julius", lastname: "Babies"},
        metadata: {
            subject: "Die Rechnung",
            senderName: "The Sender",
            senderAddress: "sender@example.com",
            sent: 1772000000,
            labels: [{name: "Rechnungen", color: "#eeeeff"}],
        },
        content: {text: "Hallo", html: "<p>Hallo</p>"},
    });
    expect((fetcher as any).mock.calls[0][0]).toBe(`/api/shares/${SHARE}`);
});

test("a locked share is metadata at most, and says a password is needed", async () => {
    answering(200, {...OPEN, needs_password: true, metadata: {...OPEN.metadata, labels: null}, content: null});

    const shared = await new SharedEmailRepository().read(SHARE);
    expect(shared.needsPassword).toBe(true);
    expect(shared.content).toBeNull();
    expect(shared.metadata?.labels).toEqual([]);
});

test("a share that hides everything still says who shared it", async () => {
    answering(200, {
        needs_password: true,
        shared_by: {firstname: "Julius", lastname: "Babies"},
        metadata: null,
        content: null,
    });

    const shared = await new SharedEmailRepository().read(SHARE);
    expect(shared.metadata).toBeNull();
    expect(shared.content).toBeNull();
    expect(shared.sharedBy).toEqual({firstname: "Julius", lastname: "Babies"});
});

test("the three ways a link can fail are told apart, because the page words them differently", async () => {
    answering(410);
    expect(new SharedEmailRepository().read(SHARE)).rejects.toBeInstanceOf(ShareExpiredError);

    answering(404);
    expect(new SharedEmailRepository().read(SHARE)).rejects.toBeInstanceOf(ShareNotFoundError);

    answering(403);
    expect(new SharedEmailRepository().open(SHARE, "hunter3")).rejects.toBeInstanceOf(WrongSharePasswordError);

    answering(500);
    expect(new SharedEmailRepository().read(SHARE)).rejects.toThrow();
});

test("opening sends the password and reads the mail back", async () => {
    const fetcher = answering(200, OPEN);

    const shared = await new SharedEmailRepository().open(SHARE, "hunter2");

    const request = (fetcher as any).mock.calls[0][1];
    expect((fetcher as any).mock.calls[0][0]).toBe(`/api/shares/${SHARE}/open`);
    expect(request.method).toBe("POST");
    expect(JSON.parse(request.body)).toEqual({password: "hunter2"});
    expect(shared.content?.html).toBe("<p>Hallo</p>");
});
