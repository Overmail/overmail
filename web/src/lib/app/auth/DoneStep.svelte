<script lang="ts" module>
    import DoneStep from "./DoneStep.svelte";
</script>

<script lang="ts">
    import {DonePlugin, useAuthentiktContext} from "@julius-babies/authentikt-svelte";
    import {goto} from "$app/navigation";
    import {_} from "svelte-i18n";
    import AuthStep from "./AuthStep.svelte";

    const auth = useAuthentiktContext();

    const plugin = auth.registerPlugin<DonePlugin>(
        "authentikt-builtin/done",
        DoneStep,
        (a, ns) => new DonePlugin(a, ns),
    );

    // Instead of the DoneRenderer's reload: a reload would land on /auth again, the app is at /.
    $effect(() => {
        if (plugin.isActive && plugin.result === null) void plugin.complete();
    });

    $effect(() => {
        const result = plugin.result;
        if (result?.type === "redirect") window.location.href = result.to;
        if (result?.type === "success") void auth.cancelFlow().then(() => goto("/"));
    });
</script>

{#if plugin.isActive}
    <AuthStep>
        <p>{$_("auth.signin.done.message")}</p>
    </AuthStep>
{/if}
