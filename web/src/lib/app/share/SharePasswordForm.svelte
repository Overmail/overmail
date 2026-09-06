<!--
    The password a share asks for, on the page itself rather than in a dialog: it is the whole
    content of the page until it is typed, and a dialog over an empty page would be a window over
    nothing.

    Where a share shows its subject, this sits where the mail would be -- which is what says that
    there is a mail here and what stands between the reader and it.
-->
<script lang="ts">
    import * as Empty from "$lib/components/ui/empty";
    import * as InputGroup from "$lib/components/ui/input-group";
    import {Spinner} from "$lib/components/ui/spinner";
    import {ArrowRightIcon, LockSimpleIcon} from "phosphor-svelte";
    import {slide} from "svelte/transition";
    import {_} from "svelte-i18n";
    import type {SharePageViewModel} from "$lib/app/share/SharePageViewModel.svelte";

    let {viewModel}: {viewModel: SharePageViewModel} = $props();

    let id = $props.id();
    let field: HTMLInputElement | null = $state(null);

    // The field is what the page is for while it is locked, so the keyboard starts there.
    $effect(() => {
        field?.focus();
    });

    /** What went wrong, worded for the reader. Null while nothing has. */
    const message = $derived.by(() => {
        switch (viewModel.unlockState.type) {
            case "wrong":
                return $_("share.locked.wrong");
            case "failed":
                return $_("share.locked.failed");
            default:
                return null;
        }
    });
</script>

<!-- An `Empty`, because that is what this is: the place the mail will be, saying why it is not
     there yet. Same dashed panel the app uses everywhere else for that. -->
<Empty.Root class="border p-8">
    <Empty.Header>
        <Empty.Media variant="icon">
            <LockSimpleIcon />
        </Empty.Media>
        <Empty.Title>{$_("share.locked.title")}</Empty.Title>
        <Empty.Description>{$_("share.locked.description")}</Empty.Description>
    </Empty.Header>

    <Empty.Content>
        <form
                class="flex w-full max-w-sm flex-col gap-2"
                onsubmit={(event) => {
                    event.preventDefault();
                    void viewModel.unlock();
                }}
        >
            <!-- Field and button in one control: it is one question with one answer, and the
                 group draws the focus ring and the invalid state around both. -->
            <InputGroup.Root>
                <InputGroup.Input
                        bind:ref={field}
                        id={"share-password-" + id}
                        type="password"
                        autocomplete="off"
                        aria-label={$_("share.locked.password")}
                        placeholder={$_("share.locked.password")}
                        aria-invalid={viewModel.unlockState.type === "wrong"}
                        value={viewModel.password}
                        oninput={(event) => viewModel.setPassword(event.currentTarget.value)}
                />

                <InputGroup.Addon align="inline-end">
                    <InputGroup.Button
                            type="submit"
                            variant="default"
                            size="icon-xs"
                            disabled={!viewModel.canUnlock}
                            aria-label={viewModel.unlocking
                                ? $_("share.locked.unlocking")
                                : $_("share.locked.submit")}
                    >
                        {#if viewModel.unlocking}
                            <Spinner />
                        {:else}
                            <ArrowRightIcon />
                        {/if}
                    </InputGroup.Button>
                </InputGroup.Addon>
            </InputGroup.Root>

            <!-- Under the field it belongs to, and only while there is something to say: a row
                 held open for a message that is usually not there is a gap with no meaning. -->
            <div aria-live="polite">
                {#if message}
                    <p class="text-destructive text-start text-sm" transition:slide={{axis: "y", duration: 140}}>
                        {message}
                    </p>
                {/if}
            </div>
        </form>
    </Empty.Content>
</Empty.Root>
