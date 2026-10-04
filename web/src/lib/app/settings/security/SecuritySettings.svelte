<script lang="ts">
    import PasswordSection from "./PasswordSection.svelte";
    import TotpSection from "./TotpSection.svelte";
</script>

<div class="flex min-w-0 flex-1 flex-col grow">
    <div class="flex flex-col gap-8">
        <PasswordSection />
        <TotpSection />
    </div>
</div>
