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

test("tries again on its own after a failed fetch", async () => {
    const answers: AuthCodeState[] = [{type: "error"}, readyFor(60_000)];
    const fetchAuthCode = mock(async () => answers.shift()!);
    const viewModel = new AuthCodeViewModel(fetchAuthCode, 10);

    await viewModel.renew();
    expect(viewModel.state.type).toBe("error");

    await Bun.sleep(50);
    viewModel.dispose();

    expect(fetchAuthCode).toHaveBeenCalledTimes(2);
    expect(viewModel.state.type).toBe("ready");
});

test("an answer that arrives after disposing schedules nothing", async () => {
    let answer: (state: AuthCodeState) => void = () => {};
    const fetchAuthCode = mock(() => new Promise<AuthCodeState>((resolve) => (answer = resolve)));
    const viewModel = new AuthCodeViewModel(fetchAuthCode, 10);

    const renewing = viewModel.renew();
    viewModel.dispose();
    answer({type: "error"});
    await renewing;
    await Bun.sleep(50);

    expect(fetchAuthCode).toHaveBeenCalledTimes(1);
});
