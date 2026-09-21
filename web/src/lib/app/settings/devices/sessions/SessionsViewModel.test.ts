import {expect, mock, test} from "bun:test";
import {SessionsViewModel} from "./SessionsViewModel.svelte";
import type {UserSession} from "$lib/repository/SessionsRepository";

const SESSION: UserSession = {
    id: "s-1",
    client: {type: "web", browser: "Firefox 131", device: "Mac", os: "macOS"},
    issuedAt: new Date("2026-09-21T10:00:00Z"),
    isCurrentSession: true,
};

test("reads the list again and again until disposed", async () => {
    const list = mock(async () => [SESSION]);
    const viewModel = new SessionsViewModel(list, 10);

    await viewModel.poll();
    expect(viewModel.sessions).toEqual([SESSION]);

    await Bun.sleep(50);
    viewModel.dispose();
    const calls = list.mock.calls.length;
    expect(calls).toBeGreaterThanOrEqual(2);

    await Bun.sleep(50);
    expect(list.mock.calls.length).toBe(calls);
});

test("a failed read keeps the list it had and tries again", async () => {
    let fail = false;
    const list = mock(async () => {
        if (fail) throw new Error("offline");
        return [SESSION];
    });
    const viewModel = new SessionsViewModel(list, 10);

    await viewModel.poll();
    fail = true;
    await Bun.sleep(25);

    expect(viewModel.failed).toBe(true);
    expect(viewModel.sessions).toEqual([SESSION]);

    fail = false;
    await Bun.sleep(25);
    viewModel.dispose();
    expect(viewModel.failed).toBe(false);
});
