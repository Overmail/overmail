import type {Component} from "svelte";
import type {OvermailPromptViewModel} from "./OvermailPromptViewModel.svelte";

export type Prompt = {
    segments: PromptSegment[];
    type: PromptMode;
}

export type PromptMode = "normal" | "read-only" | "ask-before-write";

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
    // Display name, if the sender ever used one -- otherwise just the address.
    name: string | null;
    address: string;
    avatarUrl: string | null;
}

export type PromptEmail = {
    id: string;
    subject: string;
    avatarUrl: string | null;
}

// Props every trigger window receives from PromptInput. A window may additionally export an
// instance function `handleKey(event): boolean`; the editor hands keyboard events (arrows,
// Enter) to it for as long as the window is open.
export type PromptTriggerWindowProps = {
    query: string;
    left: number;
    bottom: number;
    viewModel: OvermailPromptViewModel;
    // Replaces the trigger text (character + query) with the segment.
    onReplace: (segment: PromptSegment) => void;
    onDismiss: () => void;
};

// What PromptInput exposes through bind:this.
export type PromptInputExports = {
    focusEnd: () => void;
};

export type PromptTriggerWindowExports = {
    handleKey?: (event: KeyboardEvent) => boolean;
};

// A character that opens a query window in the prompt editor, e.g. "#" for labels.
export type PromptTriggerDefinition = {
    char: string;
    window: Component<PromptTriggerWindowProps, PromptTriggerWindowExports>;
};
