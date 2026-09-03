<script lang="ts">
    import favicon from "$lib/assets/favicon.svg";
    import {_} from 'svelte-i18n';
</script>

<!-- Fills whatever it is put in rather than the viewport: the layout hands it a `fixed inset-0`
     box, which is the one measure a phone's address bar cannot make wrong. -->
<div class="flex flex-col items-center justify-center gap-4 w-full h-full">
    <img src={favicon} alt="Logo" class="w-16 h-16" />
    <p class="text-2xl font-medium">Overmail</p>

    <div class="pt-4">
        <span class="loader" role="status" aria-label={$_('app.checkingSession')}></span>
    </div>
</div>

<style>
    .loader {
        --color-1: var(--accent);
        --color-2: var(--accent-foreground);
        --size: 1px;

        width: calc(160 * var(--size));
        height: calc(8 * var(--size));
        border-radius: calc(4 * var(--size));
        display: block;
        margin: calc(20 * var(--size)) auto;
        position: relative;
        background: var(--color-1);
        overflow: hidden;
        box-sizing: border-box;
    }

    .loader::after {
        content: '';
        position: absolute;
        top: 0;
        left: 0;
        height: 100%;
        width: calc(70 * var(--size));
        border-radius: inherit;
        background: var(--color-2);
        box-sizing: border-box;
        animation: slide 1.2s cubic-bezier(0.4, 0, 0.2, 1) infinite;
    }

    @keyframes slide {
        0% {
            transform: translateX(calc(-70 * var(--size)));
        }

        100% {
            transform: translateX(calc(160 * var(--size)));
        }
    }
</style>
