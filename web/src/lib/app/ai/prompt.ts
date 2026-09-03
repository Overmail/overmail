import type {Component} from "svelte";
import type {OvermailPromptViewModel} from "./OvermailPromptViewModel.svelte";

export type Prompt = {
    segments: PromptSegment[];
}

export type PromptSegment = {
    type: "text",
    content: string;
} | {
    type: "email",
    email: PromptEmail;
} | {
    type: "label",
    label: PromptLabel;
} | {
    type: "sender",
    sender: PromptSender;
}

export type PromptLabel = {
    id: string;
    name: string;
    color: string;
}

export type PromptSender = {
    id: string;
    // Anzeigename, falls der Absender je einen benutzt hat -- sonst nur die Adresse.
    name: string | null;
    address: string;
    avatarUrl: string | null;
}

export type PromptEmail = {
    id: string;
    subject: string;
    avatarUrl: string | null;
}

// Props, die jedes Trigger-Fenster vom PromptInput bekommt. Ein Fenster kann zusätzlich
// eine Instanz-Funktion `handleKey(event): boolean` exportieren, an die der Editor
// Tastatur-Events (Pfeile, Enter) weiterreicht, solange das Fenster offen ist.
export type PromptTriggerWindowProps = {
    query: string;
    left: number;
    bottom: number;
    viewModel: OvermailPromptViewModel;
    // Ersetzt den Trigger-Text (Zeichen + Query) durch das Segment.
    onReplace: (segment: PromptSegment) => void;
    onDismiss: () => void;
};

// Was PromptInput per bind:this nach aussen gibt.
export type PromptInputExports = {
    focusEnd: () => void;
};

export type PromptTriggerWindowExports = {
    handleKey?: (event: KeyboardEvent) => boolean;
};

// Ein Zeichen, das im Prompt-Editor ein Query-Fenster öffnet (z.B. "#" für Labels).
export type PromptTriggerDefinition = {
    char: string;
    window: Component<PromptTriggerWindowProps, PromptTriggerWindowExports>;
};
