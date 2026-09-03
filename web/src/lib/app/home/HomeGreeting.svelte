<script lang="ts">
    import {onMount} from "svelte";
    import {_} from "svelte-i18n";
    import {greetingFor} from "$lib/app/home/greeting";
    import {useRepositories} from "$lib/repository/repositories";
    import type {CurrentUser} from "$lib/repository/CurrentUserRepository";
    import {Skeleton} from "$lib/components/ui/skeleton";

    /** How often the clock is read again, see below. */
    const CLOCK_INTERVAL = 60_000;

    const {currentUser} = useRepositories();

    let user = $state<CurrentUser | null>(null);
    let now = $state(new Date());

    onMount(() => {
        currentUser.get().then((result) => (user = result));

        // Read again rather than once at setup: this screen is left open, and 18:00 must not
        // find it still saying "Guten Tag".
        const clock = setInterval(() => (now = new Date()), CLOCK_INTERVAL);
        return () => clearInterval(clock);
    });

    const greeting = $derived(greetingFor(now));
</script>

{#if user}
    <h1 class="text-3xl">{$_(`home.greeting.${greeting}`, {values: {name: user.firstname}})}</h1>
{:else}
    <!-- The name is one cached request away, so this is a blink, not a state to design for. -->
    <Skeleton class="h-9 w-72"/>
{/if}
