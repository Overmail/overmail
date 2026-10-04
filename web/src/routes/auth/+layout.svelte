<script lang="ts">
    import type {Snippet} from "svelte";
    import {dev} from "$app/environment";
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
        debug: {show_overlay: dev},
    };
</script>

<svelte:head>
    <title>Overmail Anmeldung</title>
</svelte:head>

<div class="w-screen h-screen flex flex-row">
    <div class="flex-1">
        <Authentikt {config}>
            {@render children()}
        </Authentikt>
    </div>

    <div class="flex-1 max-sm:hidden p-4">
        <img class="h-full w-full rounded-2xl object-cover" alt="" src={background} >
    </div>
</div>
