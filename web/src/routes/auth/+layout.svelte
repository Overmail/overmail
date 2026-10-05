<script lang="ts">
    import type {Snippet} from "svelte";
    import {page} from "$app/state";
    import {Authentikt, type AuthentiktConfiguration} from "@julius-babies/authentikt-svelte";
    import background from "$lib/assets/img.png"

    let {
        children,
    }: {
        children: Snippet,
    } = $props();

    // /auth is the server's uiLoginBaseUrl, so the provider lives here: it is where a flow resumes
    // from the URL. Same origin through Caddy, which is what lets the done step's cookie stick.
    const config: AuthentiktConfiguration = {
        baseUrl: new URL("/api/auth/authentikt/", page.url.origin).toString(),
        debug: {show_overlay: false},
    };
</script>

<div class="relative flex h-dvh w-screen flex-row max-sm:p-4">
    <div class="relative z-10 min-h-0 flex-1 max-sm:overflow-hidden max-sm:rounded-2xl max-sm:bg-background">
        <Authentikt {config}>
            {@render children()}
        </Authentikt>
    </div>

    <div class="flex-1 max-sm:absolute max-sm:inset-0 sm:pl-0 sm:p-4">
        <img class="h-full w-full object-cover sm:rounded-2xl" alt="" src={background} >
    </div>
</div>
