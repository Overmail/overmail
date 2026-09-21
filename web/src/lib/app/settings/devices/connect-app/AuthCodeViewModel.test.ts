import {expect, mock, test} from "bun:test";
import {AuthCodeViewModel} from "./AuthCodeViewModel.svelte";
import type {AuthCodeState} from "$lib/repository/DevicesSettingsRepository";

function readyFor(ms: number): AuthCodeState {
    return {type: "ready", url: "overmail://example/auth?code=abc", validUntil: new Date(Date.now() + ms)};
}

test("shows the fetched code", async () => {
    const viewModel = new AuthCodeViewModel(async () => readyFor(60_000));

    await viewModel.renew();

    expect(viewModel.state.type).toBe("ready");
    viewModel.dispose();
});

test("fetches the next code once the shown one runs out", async () => {
    const fetchAuthCode = mock(async () => readyFor(10));
    const viewModel = new AuthCodeViewModel(fetchAuthCode);

    await viewModel.renew();
    await Bun.sleep(50);
    viewModel.dispose();

    expect(fetchAuthCode.mock.calls.length).toBeGreaterThanOrEqual(2);
});

test("stops renewing once disposed", async () => {
    const fetchAuthCode = mock(async () => readyFor(10));
    const viewModel = new AuthCodeViewModel(fetchAuthCode);

    await viewModel.renew();
    viewModel.dispose();
    await Bun.sleep(50);

    expect(fetchAuthCode).toHaveBeenCalledTimes(1);
});
