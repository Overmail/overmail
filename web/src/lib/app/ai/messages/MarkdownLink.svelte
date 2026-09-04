<!--
    A link in an answer. It looks like the anchor it replaces, but it is a button: the text of a
    link is written by the model and does not have to match where it goes, so following one is
    only immediate inside the app. Everything else shows the url first.
-->
<script lang="ts">
    import type {Snippet} from "svelte";
    import {ArrowSquareOutIcon} from "phosphor-svelte";
    import * as Dialog from "$lib/components/ui/dialog";
    import {Button} from "$lib/components/ui/button";
    import {page} from "$app/state";
    import {goto} from "$app/navigation";
    import {_} from "svelte-i18n";

    let {
        href,
        title,
        children,
    }: {
        /** Sanitized by the markdown renderer, and unset when it dropped the url. */
        href?: string;
        title?: string;
        children?: Snippet;
    } = $props();

    let confirming = $state(false);

    // Resolved against the page, the way an anchor would: an answer may link relatively.
    const target = $derived.by(() => {
        if (!href) return null;
        try {
            return new URL(href, page.url);
        } catch {
            return null;
        }
    });

    // Same origin means this is a route of the app, so the client router owns it. Anything with
    // another scheme -- mailto:, tel: -- has no origin to match and lands in the dialog.
    const isInternal = $derived(target !== null && target.origin === page.url.origin);

    function follow() {
        if (target === null) return;

        if (isInternal) goto(`${target.pathname}${target.search}${target.hash}`);
        else confirming = true;
    }

    function open() {
        confirming = false;
        window.location.href = target!.href;
    }

    function openInNewTab() {
        confirming = false;
        // noopener: the new page has no handle on this one, and no back reference to the chat.
        window.open(target!.href, "_blank", "noopener,noreferrer");
    }
</script>

{#if target === null}
    <!-- No url to go to, so the link text is all there is. -->
    {@render children?.()}
{:else}
    <!-- inline, not the inline-block a button is: this sits in a line of text and has to wrap
         with it. The underline is what the anchor got from the message's styles. -->
    <button
            type="button"
            {title}
            onclick={follow}
            class="inline cursor-pointer text-left underline wrap-anywhere rounded-xs focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
    >{@render children?.()}</button>

    <Dialog.Root bind:open={confirming}>
        <Dialog.Content>
            <Dialog.Header>
                <Dialog.Title>{$_("ai.chat.messages.externalLink.title")}</Dialog.Title>
                <Dialog.Description>
                    {$_("ai.chat.messages.externalLink.description")}
                </Dialog.Description>
            </Dialog.Header>

            <!-- The whole url, unshortened: it is the one thing this dialog exists to show. -->
            <p class="rounded-2xl bg-muted px-3 py-2 font-mono text-xs wrap-anywhere">
                {target.href}
            </p>

            <Dialog.Footer>
                <Dialog.Close>
                    {#snippet child({props})}
                        <Button variant="ghost" {...props}>
                            {$_("ai.chat.messages.externalLink.cancel")}
                        </Button>
                    {/snippet}
                </Dialog.Close>
                <Button variant="outline" onclick={openInNewTab}>
                    <ArrowSquareOutIcon data-icon="inline-start"/>
                    {$_("ai.chat.messages.externalLink.openInNewTab")}
                </Button>
                <Button onclick={open}>{$_("ai.chat.messages.externalLink.open")}</Button>
            </Dialog.Footer>
        </Dialog.Content>
    </Dialog.Root>
{/if}
