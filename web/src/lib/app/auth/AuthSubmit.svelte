<script lang="ts">
    import {ArrowRightIcon} from "phosphor-svelte";
    import {Spinner} from "$lib/components/ui/spinner";

    let {loading = false, disabled = false, label}: {
        loading?: boolean,
        disabled?: boolean,
        label: string,
    } = $props();
</script>

<button
        type="submit"
        class="group/submit flex size-11 shrink-0 cursor-pointer items-center justify-center rounded-xl bg-primary text-primary-foreground transition-all hover:bg-primary/85 active:scale-95 disabled:cursor-not-allowed disabled:opacity-40"
        disabled={loading || disabled}
        aria-label={label}
>
    {#if loading}
        <Spinner class="size-4" />
    {:else}
        <ArrowRightIcon class="size-4 transition-transform group-hover/submit:translate-x-0.5" weight="bold" />
    {/if}
</button>
