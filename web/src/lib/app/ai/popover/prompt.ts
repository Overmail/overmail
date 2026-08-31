export type Prompt = {
    segments: PromptSegment[];
}

export type PromptSegment = {
    type: "text",
    content: string;
} | {
    type: "email",
    emailId: string;
} | {
    type: "label",
    labelId: string;
}