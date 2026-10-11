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
        SidebarMenuItem,
        useSidebar
    } from "$lib/components/ui/sidebar";
    import {Button} from "$lib/components/ui/button";
    import {SidebarSimpleIcon} from "phosphor-svelte";
    import {navItems} from "$lib/app/shell/nav";
    import {page} from "$app/state";
    import {_} from "svelte-i18n";
    import AccountList from "$lib/app/shell/navbar/accounts/AccountList.svelte";
    import UserMenu from "$lib/app/shell/navbar/UserMenu.svelte";
    import ViewList from "$lib/app/shell/navbar/views/ViewList.svelte";
    import favicon from "$lib/assets/favicon.svg";

    const sidebar = useSidebar();
</script>

<!-- On the page rather than beside it: the sidebar takes the page's background instead of one of
     its own, and no line sets it off. The colour is swapped where the component reads it, so
     everything in here that says `bg-sidebar` follows. The border only goes where the sidebar
     stands in the page -- as a sheet over it, on a narrow window, its edge is what shows it. -->
<!-- Collapsed it keeps its icons rather than leaving the screen. Clipped, because what is in it
     is laid out for the full width and is simply covered while the sidebar is narrower -- the
     page behind it shows through, so anything standing out past its edge would be on the page. -->
<Sidebar compact collapsible="icon" class="overflow-hidden [--sidebar:var(--background)] md:border-e-0!">
    <SidebarHeader>
        <!-- px-2.5 is the rows' own padding, so the logo stands in the column of their icons;
             collapsed, the row is exactly the logo, in the middle of that column. -->
        <div
                class="group/brand relative mt-2.5 mb-1.5 flex h-8 items-center gap-2 px-2.5
                       transition-[padding] duration-300 ease-[cubic-bezier(0.32,0.72,0,1)]
                       group-data-[collapsible=icon]:px-0"
        >
            <img
                    src={favicon}
                    alt=""
                    class="size-8 shrink-0 transition-opacity duration-150
                           group-data-[collapsible=icon]:group-hover/brand:opacity-0
                           group-data-[collapsible=icon]:group-has-[:focus-visible]/brand:opacity-0"
            />
            <span
                    class="font-display text-lg leading-none whitespace-nowrap transition-opacity duration-200
                           group-data-[collapsible=icon]:opacity-0"
            >
                {$_('app.name')}
            </span>

            <!-- At the end of the row, which is the edge of the sidebar: it rides that edge in as
                 the sidebar closes and comes to rest on the logo. There it only shows while the
                 row is hovered or focused -- the logo is what the collapsed sidebar is headed by,
                 and clicking it is how it opens again. Not in the sheet a narrow window shows the
                 sidebar as: that one is closed by its own means. -->
            <Button
                    variant="ghost"
                    size="icon-sm"
                    class="text-sidebar-foreground/50 hover:text-sidebar-foreground absolute end-0 top-0 size-8
                           transition-opacity duration-150 in-data-[mobile=true]:hidden
                           group-data-[collapsible=icon]:opacity-0
                           group-data-[collapsible=icon]:group-hover/brand:opacity-100
                           group-data-[collapsible=icon]:focus-visible:opacity-100"
                    aria-label={$_(sidebar.open ? "ui.sidebar.collapse" : "ui.sidebar.expand")}
                    title={$_(sidebar.open ? "ui.sidebar.collapse" : "ui.sidebar.expand")}
                    aria-expanded={sidebar.open}
                    onclick={() => sidebar.toggle()}
            >
                <SidebarSimpleIcon />
            </Button>
        </div>
    </SidebarHeader>
    <SidebarContent>
        <SidebarGroup>
            <SidebarGroupContent>
                <SidebarMenu>
                    {#each navItems as item (item.href)}
                        {@const Icon = item.icon}
                        <SidebarMenuItem>
                            <SidebarMenuButton isActive={item.matches(page.url)} tooltipContent={$_(item.key)}>
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
