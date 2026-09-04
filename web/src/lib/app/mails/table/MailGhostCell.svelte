<script lang="ts">
    import {Skeleton} from "$lib/components/ui/skeleton";

    let {
        widthClass,
        withAvatar = false,
    }: {
        widthClass: string;
        /** Matches the avatar in the sender column so a half loaded list keeps its shape. */
        withAvatar?: boolean;
    } = $props();
</script>

<div class="flex items-center gap-2">
    {#if withAvatar}
        <Skeleton class="size-6 rounded-full"/>
    {/if}
    <Skeleton class="h-4 {widthClass}"/>
</div>
