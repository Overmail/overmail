<script lang="ts">
    import * as Popover from "$lib/components/ui/popover";
    import {Input} from "$lib/components/ui/input";
    import type {AiProcessingMode} from "./NewEmailAccountViewModel.svelte.ts";
    import {CaretDownIcon, CheckIcon} from "phosphor-svelte";
    import {cn} from "$lib/utils";
    import {locale, _} from "svelte-i18n";

    let {
        mode,
        disabled = false,
        onChange,
    }: {
        mode: AiProcessingMode,
        disabled?: boolean,
        onChange: (mode: AiProcessingMode) => void,
    } = $props();

    const id = $props.id();
    let open = $state(false);

    const MODES = ["new_only", "newest", "since", "all"] as const;

    const today = () => new Date().toISOString().slice(0, 10);
    const formatDate = (date: string) =>
        new Date(date).toLocaleDateString($locale ?? undefined, {year: "numeric", month: "2-digit", day: "2-digit"});

    /**
     * The label the trigger carries: the mode, and the value it was given.
     *
     * The value belongs here rather than in a field of its own next to the control. A column wide
     * enough to hold that field permanently is mostly empty space, since two of the four modes
     * carry no value at all -- and one that only appears when it is needed makes every row a
     * different width.
     */
    const triggerLabel = $derived.by(() => {
        // Shorter than the wording in the list: the list explains the mode, the trigger only has
        // to name the setting, and it has one table column to do it in.
        if (mode.type === "newest") {
            return $_("settings.emailAccounts.new.folders.aiMode.triggerNewest", {
                values: {count: mode.count.toLocaleString($locale ?? undefined)},
            });
        }
        if (mode.type === "since") {
            return $_("settings.emailAccounts.new.folders.aiMode.triggerSince", {
                values: {date: formatDate(mode.date)},
            });
        }
        return $_(`settings.emailAccounts.new.folders.aiMode.${mode.type}`);
    });

    /** Switching keeps whatever the target mode was last given, so a mistaken click costs nothing. */
    let lastCount = $state(100);
    let lastDate = $state(today());
    $effect(() => {
        if (mode.type === "newest") lastCount = mode.count;
        if (mode.type === "since") lastDate = mode.date;
    });

    function select(type: (typeof MODES)[number]) {
        if (type === "newest") onChange({type: "newest", count: lastCount});
        else if (type === "since") onChange({type: "since", date: lastDate});
        else if (type === "all") onChange({type: "all"});
        else onChange({type: "new_only"});
    }
</script>

<Popover.Root bind:open>
    <Popover.Trigger
            {disabled}
            class={cn(
                "flex h-8 w-full flex-row items-center justify-between gap-1.5 rounded-3xl border border-transparent",
                "bg-input/50 px-3 text-sm transition-[color,box-shadow,background-color] outline-none",
                "focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/30",
                "disabled:cursor-not-allowed disabled:opacity-50",
            )}
    >
        <span class="min-w-0 truncate">{triggerLabel}</span>
        <CaretDownIcon class="text-muted-foreground size-4 shrink-0" />
    </Popover.Trigger>

    <Popover.Content class="w-64 gap-1 p-1.5" align="start">
        {#each MODES as option (option)}
            <button
                    type="button"
                    class={cn(
                        "hover:bg-accent hover:text-accent-foreground flex w-full flex-row items-center gap-2",
                        "rounded-2xl px-2.5 py-1.5 text-left text-sm outline-none",
                        "focus-visible:bg-accent focus-visible:text-accent-foreground",
                    )}
                    onclick={() => select(option)}
            >
                <CheckIcon class={cn("size-4 shrink-0", mode.type !== option && "invisible")} />
                <span>{$_(`settings.emailAccounts.new.folders.aiMode.${option}`)}</span>
            </button>
        {/each}

        <!--
          The field for the mode that is picked, right under it. Only ever one is here, so the
          popover neither grows a row per mode nor asks for a value nobody selected.
        -->
        {#if mode.type === "newest"}
            <div class="mt-1 flex flex-col gap-1.5 border-t px-2.5 pt-2.5 pb-1">
                <label class="text-muted-foreground text-xs" for={"ai-count-" + id}>
                    {$_("settings.emailAccounts.new.folders.aiMode.countLabel")}
                </label>
                <Input
                        id={"ai-count-" + id}
                        type="number"
                        min="1"
                        class="h-8"
                        value={mode.count}
                        oninput={(event) => {
                            const count = Number(event.currentTarget.value);
                            if (!Number.isFinite(count) || count < 1) return;
                            lastCount = count;
                            onChange({type: "newest", count});
                        }}
                />
            </div>
        {:else if mode.type === "since"}
            <div class="mt-1 flex flex-col gap-1.5 border-t px-2.5 pt-2.5 pb-1">
                <label class="text-muted-foreground text-xs" for={"ai-date-" + id}>
                    {$_("settings.emailAccounts.new.folders.aiMode.dateLabel")}
                </label>
                <Input
                        id={"ai-date-" + id}
                        type="date"
                        class="h-8"
                        value={mode.date}
                        oninput={(event) => {
                            const date = event.currentTarget.value;
                            if (!date) return;
                            lastDate = date;
                            onChange({type: "since", date});
                        }}
                />
            </div>
        {/if}
    </Popover.Content>
</Popover.Root>
