<!--
    An avatar whose picture is round.

    A picture whose corners hold nothing is simply clipped to the circle. One that was drawn out to
    its edges would lose that content to the clip, so it is shrunk until all of it comes inside
    instead. How much it has to give up is worked out on the server when the picture is stored, see
    `circle_padding` on `EmailAvatars`.
-->
<script lang="ts">
    import * as Avatar from "$lib/components/ui/avatar";
    import {cn} from "$lib/utils";
    import OvermailAvatar, {type OvermailAvatarProps} from "./OvermailAvatar.svelte";

    let {
        padding = null,
        class: className,
        ...rest
    }: OvermailAvatarProps & {
        /**
         * How much of its own box the picture gives up on every side, as a fraction. Null for one
         * that can simply be clipped to the circle.
         */
        padding?: number | null;
    } = $props();

    /**
     * Added on top of whatever the server worked out, for the pictures that need padding at all: a
     * logo shrunk to exactly the circle sits against its edge all the way round, and a little room
     * between the two is what makes it look placed rather than wedged in.
     */
    const BREATHING_ROOM = 0.08;

    const inset = $derived(
        padding && padding > 0 ? `${((padding + BREATHING_ROOM) * 100).toFixed(2)}%` : null
    );
</script>

<!--
    The roundness the base avatar leaves out, put back -- its fallback is round in either case.
    Before the caller's own class, so that one still wins over it.
-->
<OvermailAvatar {...rest} class={cn("rounded-full", className)}>
    {#snippet picture(url)}
        {#if inset}
            <!--
                The padding sits on a layer of its own rather than on the root, so that the
                fallback still gets the whole circle while the picture does not. contain and no
                rounding, because the point of the padding is that nothing is cut off -- cover
                would crop a picture that is not square to do its job.
            -->
            <span class="absolute inset-0 flex" style="padding: {inset}">
                <Avatar.Image src={url} alt="" class="rounded-none object-contain"/>
            </span>
        {:else}
            <!-- Nothing out in the corners, so the shadcn image's own rounding and cover do. -->
            <Avatar.Image src={url} alt=""/>
        {/if}
    {/snippet}
</OvermailAvatar>
