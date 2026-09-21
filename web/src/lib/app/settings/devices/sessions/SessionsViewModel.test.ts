import {expect, mock, test} from "bun:test";
import {SessionsViewModel} from "./SessionsViewModel.svelte";
import type {UserSession} from "$lib/repository/SessionsRepository";

const CURRENT: UserSession = {
    id: "s-1",
    client: {type: "web", browser: "Firefox 131", device: "Mac", os: "macOS"},
    issuedAt: new Date("2026-09-21T10:00:00Z"),
    isCurrentSession: true,
};

const PHONE: UserSession = {
    id: "s-2",
    client: {type: "android", device: "Pixel 8", manufacturer: "Google", os: "Android 15"},
    issuedAt: new Date("2026-09-20T10:00:00Z"),
    isCurrentSession: false,
};

/** A view model on stubs the test controls. */
function sessions(
    list: () => Promise<UserSession[]> = async () => [CURRENT, PHONE],
    revoke: (id: string) => Promise<void> = async () => {},
) {
    const spies = {list: mock(list), revoke: mock(revoke), signedOut: mock(() => {})};
    const viewModel = new SessionsViewModel(spies.list, spies.revoke, spies.signedOut, 10);
    return {viewModel, ...spies};
}

test("reads the list again and again until disposed", async () => {
    const {viewModel, list} = sessions();

    await viewModel.poll();
    expect(viewModel.sessions).toEqual([CURRENT, PHONE]);

    await Bun.sleep(50);
    viewModel.dispose();
    const calls = list.mock.calls.length;
    expect(calls).toBeGreaterThanOrEqual(2);

    await Bun.sleep(50);
    expect(list.mock.calls.length).toBe(calls);
});

test("a failed read keeps the list it had and tries again", async () => {
    let fail = false;
    const {viewModel} = sessions(async () => {
        if (fail) throw new Error("offline");
        return [CURRENT];
    });

    await viewModel.poll();
    fail = true;
    await Bun.sleep(25);

    expect(viewModel.failed).toBe(true);
    expect(viewModel.sessions).toEqual([CURRENT]);

    fail = false;
    await Bun.sleep(25);
    viewModel.dispose();
    expect(viewModel.failed).toBe(false);
});

test("revoking another session drops it from the list", async () => {
    const {viewModel, revoke, signedOut} = sessions();
    await viewModel.poll();

    await viewModel.revoke(PHONE);
    viewModel.dispose();

    expect(revoke.mock.calls[0][0]).toBe("s-2");
    expect(viewModel.sessions).toEqual([CURRENT]);
    expect(signedOut).not.toHaveBeenCalled();
});

test("revoking the current session signs this browser out", async () => {
    const {viewModel, signedOut} = sessions();
    await viewModel.poll();

    await viewModel.revoke(CURRENT);
    viewModel.dispose();

    expect(signedOut).toHaveBeenCalledTimes(1);
});

test("a failed revocation keeps the session and says so", async () => {
    const {viewModel, signedOut} = sessions(undefined, async () => {
        throw new Error("offline");
    });
    await viewModel.poll();

    await viewModel.revoke(PHONE);
    viewModel.dispose();

    expect(viewModel.sessions).toEqual([CURRENT, PHONE]);
    expect(viewModel.failed).toBe(true);
    expect(viewModel.revoking.size).toBe(0);
    expect(signedOut).not.toHaveBeenCalled();
});
