<script lang="ts">
    import type {Component, Snippet} from "svelte";

    let {icon: Icon, disabled = false, onclick, children}: {
        icon: Component<{class?: string}>,
        disabled?: boolean,
        onclick: () => void,
        children: Snippet,
    } = $props();
</script>

<button
        type="button"
        class="flex w-fit cursor-pointer items-center gap-2 text-sm text-muted-foreground transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
        {onclick}
        {disabled}
>
    <Icon class="size-4" />
    {@render children()}
</button>
