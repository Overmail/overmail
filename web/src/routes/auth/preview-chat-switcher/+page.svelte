<!--
    Wegwerf-Harness: rendert AiChatSwitcher gegen ein Fake-ViewModel, das das Paging des Servers
    nachspielt (30 pro Seite, mit Verzögerung). Damit sind Auto-Load, Windowing und
    Tastaturnavigation ohne laufenden Server und ohne Daten in der Datenbank prüfbar.
    Liegt unter /auth, weil das Root-Layout alles andere hinter der Session wegsperrt.
-->
<script lang="ts">
    import AiChatSwitcher from "$lib/app/ai/AiChatSwitcher.svelte";
    import type {AiChat, AiChatViewModel} from "$lib/app/ai/AiChatViewModel.svelte";

    const PAGE_SIZE = 30;
    const TOTAL = 120;
    const LATENCY_MS = 200;

    const minutes = (n: number) => new Date(Date.now() - n * 60_000);

    const names = [
        "Newsletter aufräumen",
        "Fristen aus der Uni-Mail",
        "Rechnungen an Buchhaltung weiterleiten",
        "Wer wartet noch auf eine Antwort von mir?",
        "Label-Vorschläge für den Studien-Ordner durchgehen und aufräumen",
        "Alte Reisebuchungen",
    ];

    // Erste Zeile ohne Namen, damit der Unbenannt-Zustand sichtbar ist. Die Abstände wachsen,
    // damit alle fünf Datumsgruppen vorkommen.
    const allChats: AiChat[] = Array.from({length: TOTAL}, (_, index) => ({
        id: `chat-${index}`,
        name: index === 0 ? null : `${names[index % names.length]} ${index}`,
        name_set_by_user: index % 3 === 0,
        created_at: minutes(3 + index * index * 0.9),
    }));

    class FakeAiChatViewModel {
        chats: AiChat[] = $state(allChats.slice(0, PAGE_SIZE));
        currentChatId: string | null = $state("chat-2");
        currentChat: AiChat | null = $derived(this.chats.find((chat) => chat.id === this.currentChatId) ?? null);
        chatsNewestFirst: AiChat[] = $derived(
            [...this.chats].sort((a, b) => b.created_at.getTime() - a.created_at.getTime())
        );

        oldestCreatedAt: Date | null = $state(
            allChats.reduce<Date>((oldest, chat) => (chat.created_at < oldest ? chat.created_at : oldest), allChats[0].created_at)
        );
        isLoadingChats: boolean = $state(false);

        hasMoreChats: boolean = $derived.by(() => {
            if (this.oldestCreatedAt === null) return false;
            const oldestLoaded = this.chatsNewestFirst.at(-1);
            if (oldestLoaded === undefined) return true;
            return oldestLoaded.created_at.getTime() > this.oldestCreatedAt.getTime();
        });

        loadMoreChats() {
            if (this.isLoadingChats || !this.hasMoreChats) return;

            this.isLoadingChats = true;
            const loaded = this.chats.length;
            setTimeout(() => {
                this.chats = [...this.chats, ...allChats.slice(loaded, loaded + PAGE_SIZE)];
                this.isLoadingChats = false;
                console.log(`loadMoreChats() -> ${this.chats.length}/${TOTAL}`);
            }, LATENCY_MS);
        }
    }

    const viewModel = new FakeAiChatViewModel() as unknown as AiChatViewModel;
    const narrowViewModel = new FakeAiChatViewModel() as unknown as AiChatViewModel;
</script>

<div class="flex flex-col gap-8 p-8">
    <!-- Breite wie im Popover: Popover.Content ist md:w-2xl. -->
    <div class="w-2xl border border-dashed border-destructive/40">
        <AiChatSwitcher {viewModel}/>
    </div>

    <!-- Schmal, damit die Kürzung überhaupt greifen muss. -->
    <div class="w-72 border border-dashed border-destructive/40">
        <AiChatSwitcher viewModel={narrowViewModel}/>
    </div>
</div>
