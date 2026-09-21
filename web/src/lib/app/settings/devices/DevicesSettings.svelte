<script lang="ts">
    import ConnectAppSection from "./connect-app/ConnectAppSection.svelte";
    import ActiveSessionsSection from "./sessions/ActiveSessionsSection.svelte";
</script>

<div class="flex min-w-0 flex-1 flex-col grow">
    <div class="flex flex-col gap-4">
        <ConnectAppSection />
        <ActiveSessionsSection />
    </div>
</div>
