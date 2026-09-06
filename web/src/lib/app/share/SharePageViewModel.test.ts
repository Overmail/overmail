import {expect, mock, test} from "bun:test";
import {SharePageViewModel} from "./SharePageViewModel.svelte";
import {
    ShareExpiredError,
    ShareNotFoundError,
    WrongSharePasswordError,
    type SharedEmail,
    type SharedEmailRepository,
} from "$lib/repository/SharedEmailRepository";

const SHARE = "s-1";

const METADATA = {
    subject: "Die Rechnung",
    senderName: "The Sender",
    senderAddress: "sender@example.com",
    sent: 1772000000,
    labels: [],
};

const SHARED_BY = {firstname: "Julius", lastname: "Babies"};

const OPEN: SharedEmail = {
    needsPassword: false,
    sharedBy: SHARED_BY,
    metadata: METADATA,
    content: {text: "Hallo", html: "<p>Hallo</p>"},
};

const LOCKED: SharedEmail = {needsPassword: true, sharedBy: SHARED_BY, metadata: METADATA, content: null};

/** A page whose link the test answers for. */
function page(overrides: Partial<SharedEmailRepository> = {}) {
    const shares = {
        read: mock(async () => OPEN),
        open: mock(async () => OPEN),
        ...overrides,
    } as unknown as SharedEmailRepository;

    return {viewModel: new SharePageViewModel(SHARE, shares), shares};
}

test("a link without a password shows the mail straight away", async () => {
    const {viewModel, shares} = page();

    await viewModel.load();

    expect(viewModel.locked).toBe(false);
    expect(viewModel.shared?.content?.html).toBe("<p>Hallo</p>");
    expect((shares.read as any).mock.calls[0][0]).toBe(SHARE);
});

test("a locked link shows what it may and asks for the password", async () => {
    const {viewModel} = page({read: mock(async () => LOCKED)});

    await viewModel.load();

    expect(viewModel.locked).toBe(true);
    expect(viewModel.shared?.metadata?.subject).toBe("Die Rechnung");
    expect(viewModel.shared?.content).toBeNull();
    // Nothing typed yet, so there is nothing to send.
    expect(viewModel.canUnlock).toBe(false);
});

test("the password is sent and the mail takes the place of the question", async () => {
    const {viewModel, shares} = page({read: mock(async () => LOCKED)});
    await viewModel.load();

    viewModel.setPassword("hunter2");
    expect(viewModel.canUnlock).toBe(true);
    expect(await viewModel.unlock()).toBe(true);

    expect((shares.open as any).mock.calls[0]).toEqual([SHARE, "hunter2"]);
    expect(viewModel.locked).toBe(false);
    expect(viewModel.shared?.content?.html).toBe("<p>Hallo</p>");
    // Nothing left to send it with, so it is not kept around either.
    expect(viewModel.password).toBe("");
});

test("a wrong password marks the field and leaves the page as it was", async () => {
    const {viewModel} = page({
        read: mock(async () => LOCKED),
        open: mock(async () => Promise.reject(new WrongSharePasswordError())),
    });
    await viewModel.load();

    viewModel.setPassword("hunter3");
    expect(await viewModel.unlock()).toBe(false);

    expect(viewModel.unlockState).toEqual({type: "wrong"});
    expect(viewModel.locked).toBe(true);
    // Still readable: a mistyped password takes nothing away that was already shown.
    expect(viewModel.shared?.metadata?.subject).toBe("Die Rechnung");

    // Typing is the answer to the message, so it goes as soon as the field changes.
    viewModel.setPassword("hunter");
    expect(viewModel.unlockState).toEqual({type: "idle"});
});

test("a link that ran out or never was is the page's news, not the field's", async () => {
    const expired = page({read: mock(async () => Promise.reject(new ShareExpiredError()))});
    await expired.viewModel.load();
    expect(expired.viewModel.state).toEqual({type: "expired"});

    const missing = page({read: mock(async () => Promise.reject(new ShareNotFoundError()))});
    await missing.viewModel.load();
    expect(missing.viewModel.state).toEqual({type: "missing"});

    const broken = page({read: mock(async () => Promise.reject(new Error("nope")))});
    await broken.viewModel.load();
    expect(broken.viewModel.state).toEqual({type: "failed"});
});

test("a link that runs out while the password is typed says so on the whole page", async () => {
    const {viewModel} = page({
        read: mock(async () => LOCKED),
        open: mock(async () => Promise.reject(new ShareExpiredError())),
    });
    await viewModel.load();

    viewModel.setPassword("hunter2");
    expect(await viewModel.unlock()).toBe(false);
    expect(viewModel.state).toEqual({type: "expired"});
});
