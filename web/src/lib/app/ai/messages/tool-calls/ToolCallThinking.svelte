<!-- What the model reasoned before it acted, shown like the tools it used. -->
<script lang="ts">
    import {BrainIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";

    let {content}: {content: string} = $props();
</script>

<span class="flex items-start gap-1.5 text-muted-foreground">
    <BrainIcon class="mt-0.5 size-4 shrink-0"/>
    <span class="min-w-0">
        <span class="shrink-0">{$_("ai.chat.messages.thinking")}</span>
        {#if content !== ""}
            <span class="text-muted-foreground/80 italic"> {content}</span>
        {/if}
    </span>
</span>
