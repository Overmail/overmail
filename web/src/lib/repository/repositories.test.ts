import {expect, test} from "bun:test";
import {createRepositories, repositories} from "./repositories";
import {CurrentUserRepository} from "./CurrentUserRepository";

test("the app's set holds one instance of each repository", () => {
    expect(repositories.currentUser).toBeInstanceOf(CurrentUserRepository);
    // Same object on every read: this is what makes the caches inside them shared.
    expect(repositories.currentUser).toBe(repositories.currentUser);
});

test("a test can replace a single repository and keeps the rest", () => {
    const stub = new CurrentUserRepository();
    const container = createRepositories({currentUser: stub});

    expect(container.currentUser).toBe(stub);
    expect(container.emails).not.toBe(repositories.emails);
});
