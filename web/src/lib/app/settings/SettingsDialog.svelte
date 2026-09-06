<script lang="ts">
    import {page} from "$app/state";
    import * as Dialog from "$lib/components/ui/dialog";
    import * as Sidebar from "$lib/components/ui/sidebar";
    import * as Breadcrumb from "$lib/components/ui/breadcrumb";
    import {goto} from "$app/navigation";
    import {EnvelopeSimpleIcon} from "phosphor-svelte";
    import EmailAccountsSettings from "$lib/app/settings/email-accounts/EmailAccountsSettings.svelte";

    let showSettingsDialog = $derived(page.url.searchParams.has("settings"));

    function closeSettingsDialog() {
        const url = new URL(page.url);
        url.searchParams.delete("settings");
        goto(url, {replaceState: true, noScroll: true});
    }

    const nav = [
        {name: "E-Mail-Konten", icon: EnvelopeSimpleIcon, key: "email-accounts"},
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
    <Dialog.Content
            class="overflow-hidden p-0 md:max-h-125 md:max-w-175 lg:max-w-200"
            trapFocus={false}
    >
        <Dialog.Title class="sr-only">Settings</Dialog.Title>
        <Dialog.Description class="sr-only">Customize your settings here.</Dialog.Description>
        <Sidebar.Provider class="items-start">
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
            <main class="flex h-120 flex-1 flex-col overflow-hidden pt-5">
                <header
                        class="flex shrink-0 items-center gap-2 transition-[width,height] ease-linear group-has-data-[collapsible=icon]/sidebar-wrapper:h-12"
                >
                    <div class="flex items-center gap-2 px-4">
                        <Breadcrumb.Root>
                            <Breadcrumb.List>
                                <Breadcrumb.Item class="hidden md:block">
                                    <Breadcrumb.Link href="##">Settings</Breadcrumb.Link>
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
                    {/if}
                </div>
            </main>
        </Sidebar.Provider>
    </Dialog.Content>
</Dialog.Root>
