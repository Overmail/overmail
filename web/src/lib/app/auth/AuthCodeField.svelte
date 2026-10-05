<script lang="ts">
    import {tick, untrack} from "svelte";
    import * as InputOTP from "$lib/components/ui/input-otp";
    import {shake} from "./shake";

    let {
        value = $bindable(""),
        id,
        length,
        disabled = false,
        label,
        oninput,
        oncomplete,
    }: {
        value?: string,
        /** Of the pin input's hidden <input>, the one that takes the keyboard. */
        id: string,
        length: number,
        disabled?: boolean,
        label: string,
        oninput?: () => void,
        oncomplete?: () => void,
    } = $props();

    /** How long the boxes stay red after a code is turned down; the message below them stays. */
    const REJECTED_MS = 1500;

    let field = $state<HTMLElement | null>(null);
    let rejected = $state(false);
    let rejectedTimeout: ReturnType<typeof setTimeout> | undefined;

    /**
     * For a code that was turned down: the boxes shake and are red for a moment, and the focus is
     * back in them. The input was disabled while the code was checked, which took it away.
     */
    export async function reject() {
        rejected = true;
        shake(field);
        clearTimeout(rejectedTimeout);
        rejectedTimeout = setTimeout(() => (rejected = false), REJECTED_MS);
        await tick();
        document.getElementById(id)?.focus();
    }
</script>

<!-- The one thing on the page to type into, so the cursor starts there.
     bits-ui calls onComplete from inside an effect. Untracked, or whatever the submit reads (the
     step's status, its rate limit) becomes that effect's dependency: every answer re-runs it, it
     still sees the code as just completed and sends it again, endlessly. -->
<!-- svelte-ignore a11y_autofocus -->
<InputOTP.Root
        bind:ref={field}
        bind:value
        inputId={id}
        maxlength={length}
        pattern={"^\\d+$"}
        {disabled}
        onValueChange={() => {
            if (value) rejected = false;
            oninput?.();
        }}
        onComplete={() => untrack(() => oncomplete?.())}
        aria-label={label}
        class="min-w-0 flex-1"
        autofocus
>
    {#snippet children({cells})}
        <!-- The slots are apart, so each marks itself invalid; the group's ring around all of them
             would be drawn across the gaps. -->
        <InputOTP.Group class="w-full gap-2 has-aria-invalid:ring-0">
            {#each cells as cell (cell)}
                <!-- Filled boxes like the text fields, apart instead of joined; reddish for a moment
                     when a code is turned down. -->
                <InputOTP.Slot
                        {cell}
                        aria-invalid={rejected}
                        class="size-11 min-w-0 flex-1 rounded-xl border border-transparent bg-muted text-base font-medium transition-[color,background-color,border-color,box-shadow] first:rounded-xl last:rounded-xl data-[active=true]:ring-ring/20 aria-invalid:border-transparent aria-invalid:bg-destructive/10"
                />
            {/each}
        </InputOTP.Group>
    {/snippet}
</InputOTP.Root>
