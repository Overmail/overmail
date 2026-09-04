import {plugin} from "bun";
import {compileModule} from "svelte/compiler";

/**
 * Lets `bun test` import a `.svelte.ts` module.
 *
 * Runes are a compiler feature: `$state` and `$derived` are not functions that exist at runtime,
 * so a repository or view model written with them cannot be imported by a plain test runner. Vite
 * runs the svelte plugin over these files; this does the same two steps for bun -- strip the
 * types, then compile the runes into their runtime calls.
 *
 * Effects are the one thing this does not buy: `$effect` needs a root to run in, which only a
 * rendered component (or `$effect.root`) provides. State and derived values work as they do in
 * the app.
 */
plugin({
    name: "svelte modules",
    setup(build) {
        const transpiler = new Bun.Transpiler({loader: "ts"});

        build.onLoad({filter: /\.svelte\.ts$/}, async (args) => {
            const source = await Bun.file(args.path).text();
            const javascript = transpiler.transformSync(source);
            const compiled = compileModule(javascript, {filename: args.path, generate: "client"});

            return {contents: compiled.js.code, loader: "js"};
        });
    },
});
