<script lang="ts">
    import {page} from "$app/state";
    import * as Dialog from "$lib/components/ui/dialog";
    import * as Sidebar from "$lib/components/ui/sidebar";
    import * as Breadcrumb from "$lib/components/ui/breadcrumb";
    import {goto} from "$app/navigation";
    import {BrainIcon, EnvelopeSimpleIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import EmailAccountsSettings from "$lib/app/settings/email-accounts/EmailAccountsSettings.svelte";
    import KnowledgeSettings from "$lib/app/settings/knowledge/KnowledgeSettings.svelte";

    let showSettingsDialog = $derived(page.url.searchParams.has("settings"));

    function closeSettingsDialog() {
        const url = new URL(page.url);
        url.searchParams.delete("settings");
        goto(url, {replaceState: true, noScroll: true});
    }

    const nav = [
        {name: $_("settings.emailAccounts.title"), icon: EnvelopeSimpleIcon, key: "email-accounts"},
        {name: $_("settings.knowledge.title"), icon: BrainIcon, key: "knowledge"},
    ];

    const currentNavItem = $derived(nav.find((item) => item.key === page.url.searchParams.get("settings")) ?? nav[0]);

    function navigateToPage(key: typeof nav[number]['key']) {
        const url = new URL(page.url);
        url.searchParams.set("settings", key);
        goto(url, {replaceState: false, noScroll: true});
    }
</script>

<Dialog.Root
        open={showSettingsDialog}
        onOpenChangeComplete={(open) => {
            if (!open) {
                closeSettingsDialog();
            }
        }}
>
    <!--
      Every step keeps the gap to the window edge: a plain `max-w-*` here overrides the
      `max-w-[calc(100%-2rem)]` the dialog comes with, and between 1024px and 1088px wide that
      left the dialog running edge to edge with no margin at all.
    -->
    <Dialog.Content
            class="overflow-hidden p-0 md:max-h-150 md:max-w-[min(50rem,calc(100%_-_2rem))]
                   lg:max-w-[min(68rem,calc(100%_-_2rem))] xl:max-w-[min(80rem,calc(100%_-_2rem))]"
            trapFocus={false}
    >
        <Dialog.Title class="sr-only">{$_("settings.title")}</Dialog.Title>
        <Dialog.Description class="sr-only">{$_("settings.description")}</Dialog.Description>
        <!--
          `min-w-0` here too: this is a grid item of the dialog and defaults to `min-width: auto`,
          so it grows to whatever is inside it. A wide table pushed it past the dialog, and the
          page went with it.
        -->
        <Sidebar.Provider class="min-w-0 items-start">
            <Sidebar.Root collapsible="none" class="hidden md:flex pt-1">
                <Sidebar.Content>
                    <Sidebar.Group>
                        <Sidebar.GroupContent>
                            <Sidebar.Menu>
                                {#each nav as item (item.name)}
                                    {@const Icon = item.icon}
                                    <Sidebar.MenuItem>
                                        <Sidebar.MenuButton isActive={item.key === currentNavItem.key}>
                                            {#snippet child({ props })}
                                                <button onclick={() => navigateToPage(item.key)} {...props}>
                                                    <Icon />
                                                    <span>{item.name}</span>
                                                </button>
                                            {/snippet}
                                        </Sidebar.MenuButton>
                                    </Sidebar.MenuItem>
                                {/each}
                            </Sidebar.Menu>
                        </Sidebar.GroupContent>
                    </Sidebar.Group>
                </Sidebar.Content>
            </Sidebar.Root>
            <!--
              `min-w-0`: a flex item defaults to `min-width: auto`, so it refuses to shrink below
              its content and the `overflow-hidden` beside it never gets to clip anything. A wide
              table in here pushed this past the dialog and took the page with it.
            -->
            <main class="flex h-145 min-w-0 flex-1 flex-col overflow-hidden pt-5">
                <header
                        class="flex shrink-0 items-center gap-2 transition-[width,height] ease-linear group-has-data-[collapsible=icon]/sidebar-wrapper:h-12"
                >
                    <div class="flex items-center gap-2 px-4">
                        <Breadcrumb.Root>
                            <Breadcrumb.List>
                                <Breadcrumb.Item class="hidden md:block">
                                    <Breadcrumb.Link href="##">{$_("settings.title")}</Breadcrumb.Link>
                                </Breadcrumb.Item>
                                <Breadcrumb.Separator class="hidden md:block" />
                                <Breadcrumb.Item>
                                    <Breadcrumb.Page>{currentNavItem.name}</Breadcrumb.Page>
                                </Breadcrumb.Item>
                            </Breadcrumb.List>
                        </Breadcrumb.Root>
                    </div>
                </header>
                <div class="flex flex-1 flex-col gap-4 overflow-y-auto py-5 px-4">
                    {#if currentNavItem.key === "email-accounts"}
                        <EmailAccountsSettings />
                    {:else if currentNavItem.key === "knowledge"}
                        <KnowledgeSettings />
                    {/if}
                </div>
            </main>
        </Sidebar.Provider>
    </Dialog.Content>
</Dialog.Root>
