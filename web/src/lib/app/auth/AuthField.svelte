<script lang="ts">
    import type {Component, Snippet} from "svelte";
    import type {HTMLInputAttributes} from "svelte/elements";

    let {
        value = $bindable(""),
        ref = $bindable(),
        icon: Icon,
        invalid = false,
        trailing,
        ...rest
    }: Omit<HTMLInputAttributes, "value"> & {
        value?: string,
        ref?: HTMLInputElement,
        icon: Component<{class?: string}>,
        invalid?: boolean,
        /** Inside the box after the input, e.g. a button that reveals the password. */
        trailing?: Snippet,
    } = $props();
</script>

<!-- The whole box is the field and takes the focus ring, the icon included. -->
<label
        class={[
            "group flex h-11 min-w-0 flex-1 items-center gap-2.5 rounded-xl border border-transparent bg-muted ps-3.5",
            trailing ? "pe-1" : "pe-3.5",
            "transition-[border-color,box-shadow,background-color] duration-200",
            "focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/20",
            invalid && "border-destructive focus-within:border-destructive focus-within:ring-destructive/15",
        ]}
>
    <Icon class="size-4 shrink-0 text-muted-foreground transition-colors group-focus-within:text-foreground" />
    <input
            bind:this={ref}
            bind:value
            class="h-full min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
            aria-invalid={invalid}
            autocapitalize="none"
            spellcheck="false"
            {...rest}
    />
    {@render trailing?.()}
</label>
