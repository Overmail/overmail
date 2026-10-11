<script lang="ts">
    import {
        Sidebar,
        SidebarContent,
        SidebarFooter,
        SidebarGroup,
        SidebarGroupContent,
        SidebarHeader,
        SidebarMenu,
        SidebarMenuButton,
        SidebarMenuItem
    } from "$lib/components/ui/sidebar";
    import {UserCircleIcon} from "phosphor-svelte";
    import {navItems} from "$lib/app/shell/nav";
    import {page} from "$app/state";
    import {onMount} from "svelte";
    import {_} from "svelte-i18n";
    import UserMenu from "$lib/app/shell/navbar/UserMenu.svelte";
    import ViewList from "$lib/app/shell/navbar/views/ViewList.svelte";
    import favicon from "$lib/assets/favicon.svg";
    import {useRepositories} from "$lib/repository/repositories";
    import {inboxLabel, type Inbox} from "$lib/repository/InboxRepository";
    import {accountView} from "$lib/app/views/predefinedViews";
    import {inlineViewHref, inlineViewParam, VIEW_PARAM} from "$lib/app/views/viewPath";

    const {inboxes} = useRepositories();

    let accounts = $state<Inbox[]>([]);

    // Read once, in the browser: the list needs the session, and the sidebar is there for as long
    // as the app is. An account connected since shows up with the next load.
    onMount(() => {
        inboxes.list().then((list) => (accounts = list)).catch((error) => console.error(error));
    });
</script>

<Sidebar compact>
    <SidebarHeader>
        <!-- px-2.5 is the rows' own padding, so the logo stands in the column of their icons. -->
        <div class="flex h-9 items-center gap-2 px-2.5 mt-3 pb-2">
            <img src={favicon} alt="" class="size-8" />
            <span class="font-display text-lg leading-none">{$_('app.name')}</span>
        </div>
    </SidebarHeader>
    <SidebarContent>
        <SidebarGroup>
            <SidebarGroupContent>
                <SidebarMenu>
                    {#each navItems as item (item.href)}
                        {@const Icon = item.icon}
                        <SidebarMenuItem>
                            <SidebarMenuButton isActive={item.matches(page.url)}>
                                {#snippet child({ props })}
                                    <a href={item.href} {...props}>
                                        <Icon />
                                        <span>{$_(item.key)}</span>
                                    </a>
                                {/snippet}
                            </SidebarMenuButton>
                        </SidebarMenuItem>
                    {/each}
                </SidebarMenu>
            </SidebarGroupContent>
        </SidebarGroup>

        <!-- One row per connected mail account, each a listing the app builds: the account as a
             whole, see `accountView`, spelled out in the link. -->
        {#if accounts.length > 0}
            <SidebarGroup>
                <SidebarGroupContent>
                    <SidebarMenu>
                        {#each accounts as account (account.id)}
                            {@const view = accountView(account.id)}
                            <SidebarMenuItem>
                                <SidebarMenuButton isActive={page.url.pathname === "/" && page.url.searchParams.get(VIEW_PARAM) === inlineViewParam(view)}>
                                    {#snippet child({ props })}
                                        <a href={inlineViewHref(view, page.url)} {...props}>
                                            <UserCircleIcon />
                                            <span>{inboxLabel(account)}</span>
                                        </a>
                                    {/snippet}
                                </SidebarMenuButton>
                            </SidebarMenuItem>
                        {/each}
                    </SidebarMenu>
                </SidebarGroupContent>
            </SidebarGroup>
        {/if}

        <SidebarGroup>
            <ViewList />
        </SidebarGroup>
    </SidebarContent>

    <SidebarFooter>
        <UserMenu />
    </SidebarFooter>
</Sidebar>
