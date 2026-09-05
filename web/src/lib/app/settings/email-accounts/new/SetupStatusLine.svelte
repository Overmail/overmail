<script lang="ts">
    import type {Component} from "svelte";
    import {cn} from "$lib/utils";

    let {
        icon: Icon,
        message,
        tone = "",
    }: {
        icon: Component<{class?: string}>,
        message: string,
        tone?: string,
    } = $props();
</script>

<!--
  The icon sits on the first line of the message rather than in the middle of a wrapped one:
  `mt-0.5` is what centres a 16px icon on a 20px line.
-->
<div class={cn("flex flex-row items-start gap-2 text-sm", tone)}>
    <Icon class="mt-0.5 size-4 shrink-0" />
    <span>{message}</span>
</div>
