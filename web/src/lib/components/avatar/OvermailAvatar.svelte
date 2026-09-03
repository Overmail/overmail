<!--
    The avatar of a correspondent, everywhere one is shown: the picture the server found for the
    address, shown whole, or initials from its name while there is none.

    Whole means whole -- the picture is neither rounded off nor cropped to fill the box, so nothing
    a sender put in their logo goes missing. OvermailCircularAvatar is the variant that does round
    it, and it shrinks whatever would not survive that rather than clipping it.
-->
<script lang="ts" module>
    import type {Snippet} from "svelte";

    /** Shared with OvermailCircularAvatar, which passes all of it straight through. */
    export type OvermailAvatarProps = {
        /** Null while the server has nothing for the address, which is when the fallback shows. */
        url: string | null | undefined;
        /** What the initials are taken from: the display name, or the address without one. */
        name?: string | null;
        size?: "sm" | "default" | "lg";
        /** Sits in a line of text rather than beside it, for the chips in a chat message. */
        inline?: boolean;
        class?: string;
        fallbackClass?: string;
        /** Shown instead of initials -- an icon, where initials would repeat the label next to them. */
        fallback?: Snippet;
    };
</script>

<script lang="ts">
    import * as Avatar from "$lib/components/ui/avatar";
    import {cn, initials} from "$lib/utils";

    let {
        url,
        name = null,
        size = "default",
        inline = false,
        class: className,
        fallbackClass,
        fallback,
        picture,
    }: OvermailAvatarProps & {
        /**
         * How the picture itself is laid out, given its url. Only OvermailCircularAvatar passes
         * one; without it the picture is shown as it is, square corners and all.
         */
        picture?: Snippet<[string]>;
    } = $props();
</script>

<!-- rounded-none: the shadcn root is a circle, and this one is not. -->
<Avatar.Root
        {size}
        border={false}
        class={cn("rounded-none", inline && "inline-flex align-[-0.2em]", className)}
>
    {#snippet child({props})}
        <!-- A span rather than the div the primitive renders: this also goes inside a line of text. -->
        <span {...props}>
            {#if url}
                <!--
                    Overriding what the shadcn image brings: no rounding, and contain rather than
                    cover, because both of those exist to fill a circle and this one is not one.
                -->
                {#if picture}
                    {@render picture(url)}
                {:else}
                    <Avatar.Image src={url} alt="" class="rounded-none object-contain"/>
                {/if}
            {/if}
            <Avatar.Fallback
                    class={cn(
                        // Round and ringed even where the picture is not: initials are ours to
                        // draw, and a bare square of them would read as a broken image.
                        "rounded-full border border-border",
                        size === "sm" && "text-[0.625rem]",
                        size === "lg" && "text-base",
                        fallbackClass,
                    )}
            >
                {#if fallback}{@render fallback()}{:else}{initials(name ?? "")}{/if}
            </Avatar.Fallback>
        </span>
    {/snippet}
</Avatar.Root>
