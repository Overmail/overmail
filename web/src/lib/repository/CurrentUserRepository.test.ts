import {expect, mock, test} from "bun:test";
import {CurrentUserRepository} from "./CurrentUserRepository";

function answering(status: number, body: unknown = null) {
    const fetcher = mock(async () => new Response(body === null ? "" : JSON.stringify(body), {status}));
    globalThis.fetch = fetcher as unknown as typeof fetch;
    return fetcher;
}

const JULIUS = {id: "u-1", firstname: "Julius", lastname: "Babies"};

test("asks once, no matter how many callers there are", async () => {
    const fetcher = answering(200, JULIUS);
    const repository = new CurrentUserRepository();

    // Two callers before the first answer is in, one after.
    const [first, second] = await Promise.all([repository.get(), repository.get()]);
    const third = await repository.get();

    expect(fetcher).toHaveBeenCalledTimes(1);
    expect((fetcher as any).mock.calls[0][0]).toBe("/api/users/me");
    expect(first).toEqual(JULIUS);
    expect(second).toEqual(JULIUS);
    expect(third).toEqual(JULIUS);
});

test("forget sends the next caller back to the server", async () => {
    const fetcher = answering(200, JULIUS);
    const repository = new CurrentUserRepository();

    await repository.get();
    repository.forget();
    await repository.get();

    expect(fetcher).toHaveBeenCalledTimes(2);
});

test("signed out is null, and is not remembered", async () => {
    answering(401);
    const repository = new CurrentUserRepository();

    expect(await repository.get()).toBeNull();

    const fetcher = answering(200, JULIUS);
    expect(await repository.get()).toEqual(JULIUS);
    expect(fetcher).toHaveBeenCalledTimes(1);
});

test("a broken answer throws and is not remembered", async () => {
    answering(500);
    const repository = new CurrentUserRepository();

    await expect(repository.get()).rejects.toThrow("500");

    const fetcher = answering(200, JULIUS);
    expect(await repository.get()).toEqual(JULIUS);
    expect(fetcher).toHaveBeenCalledTimes(1);
});
