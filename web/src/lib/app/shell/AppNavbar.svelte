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
    import {navItems} from "$lib/app/shell/nav";
    import {page} from "$app/state";
    import {_} from "svelte-i18n";
    import AccountList from "$lib/app/shell/navbar/accounts/AccountList.svelte";
    import UserMenu from "$lib/app/shell/navbar/UserMenu.svelte";
    import ViewList from "$lib/app/shell/navbar/views/ViewList.svelte";
    import favicon from "$lib/assets/favicon.svg";
</script>

<!-- On the page rather than beside it: the sidebar takes the page's background instead of one of
     its own, and no line sets it off. The colour is swapped where the component reads it, so
     everything in here that says `bg-sidebar` follows. The border only goes where the sidebar
     stands in the page -- as a sheet over it, on a narrow window, its edge is what shows it. -->
<Sidebar compact class="[--sidebar:var(--background)] md:border-e-0!">
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

        <AccountList />

        <SidebarGroup>
            <ViewList />
        </SidebarGroup>
    </SidebarContent>

    <SidebarFooter class="mb-1.5">
        <UserMenu />
    </SidebarFooter>
</Sidebar>
