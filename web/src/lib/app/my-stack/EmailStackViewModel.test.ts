import {expect, test} from "bun:test";
import {EmailStackViewModel} from "./EmailStackViewModel.svelte";
import {EmailRepository} from "$lib/repository/EmailRepository.svelte";
import type {SocketLike} from "$lib/repository/ReconnectingSocket";

class FakeSocket implements SocketLike {
    onopen: (() => void) | null = null;
    onclose: ((event: {wasClean: boolean}) => void) | null = null;
    onmessage: ((event: {data: string}) => void) | null = null;
    closed = false;

    readonly sent: Record<string, unknown>[] = [];

    send(data: string) {
        this.sent.push(JSON.parse(data));
    }

    close() {
        this.closed = true;
        this.onclose?.({wasClean: true});
    }

    deliver(message: unknown) {
        this.onmessage?.({data: JSON.stringify(message)});
    }
}

function wireMail(id: string, archiveState = "unarchive") {
    return {
        id,
        subject: `Subject of ${id}`,
        sent: 1_700_000_000,
        is_read: false,
        preview: "Wie die Mail beginnt",
        archive_state: archiveState,
        sender: {
            id: "s-1",
            name: "The Sender",
            address: "sender@example.com",
            avatar_url: null,
            avatar_padding: null,
        },
        to: [],
        cc: [],
        bcc: [],
        labels: [],
    };
}

/**
 * The two sockets the pile runs on, both faked: the stack socket says which mails, the content
 * socket says what they are.
 */
function stack() {
    let content: FakeSocket | null = null;
    let pile: FakeSocket | null = null;

    const mails = new EmailRepository({
        graceMs: 1,
        reconnectDelays: [1],
        open: () => (content = new FakeSocket()),
    });

    const viewModel = new EmailStackViewModel(
        mails,
        {getBody: async (id: string) => ({text: `body of ${id}`, html: null})} as never,
        () => (pile = new FakeSocket())
    );

    return {
        viewModel,
        content: () => content!,
        pile: () => pile!,
        /** A batch of the pile, followed by the metadata of every mail in it. */
        async serve(ids: string[], archiveStates: Record<string, string> = {}) {
            pile!.deliver({type: "data.emails", email_ids: ids});
            await settle();
            content!.deliver({
                type: "data.emails",
                emails: ids.map((id) => wireMail(id, archiveStates[id] ?? "unarchive")),
            });
            await settle();
        },
    };
}

/** Lets the queued flush, the socket messages and the body fetches run. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 20));

test("a batch of ids turns into cards once the mails and bodies are here", async () => {
    const {viewModel, content, serve} = stack();

    await serve(["m-1", "m-2"]);

    // The ids came from the pile, everything on the card from the content socket.
    expect(content().sent).toEqual([{type: "subscribe.emails", ids: ["m-1", "m-2"]}]);
    expect(viewModel.emails.map((email) => email.id)).toEqual(["m-1", "m-2"]);
    expect(viewModel.emails[0].subject).toBe("Subject of m-1");
    expect(viewModel.emails[0].body.text).toBe("body of m-1");
    expect(viewModel.currentEmailId).toBe("m-1");
});

test("a mail the next batch repeats is not added twice", async () => {
    const {viewModel, serve} = stack();

    await serve(["m-1", "m-2"]);
    // The cursor includes its own second, so a batch starts again with the last mail of the one
    // before it -- and after a reconnect the pile starts over from the top.
    await serve(["m-2", "m-3"]);

    expect(viewModel.emails.map((email) => email.id)).toEqual(["m-1", "m-2", "m-3"]);
});

test("a mail that left the mailbox elsewhere disappears from the pile", async () => {
    const {viewModel, content, serve} = stack();

    await serve(["m-1", "m-2"]);
    // What the classification agent filing it as spam looks like from here.
    content().deliver({type: "data.emails", emails: [wireMail("m-1", "spam")]});
    await settle();

    expect(viewModel.emails.map((email) => email.id)).toEqual(["m-2"]);
    expect(viewModel.currentEmailId).toBe("m-2");
});

test("a mail archived here stays for its animation, and the pile is told", async () => {
    const {viewModel, pile, content, serve} = stack();

    await serve(["m-1", "m-2"]);
    viewModel.onArchiveOrUnarchiveEmail();
    // The server answers the write with the mail's new state.
    content().deliver({type: "data.emails", emails: [wireMail("m-1", "archive")]});
    await settle();

    // A short pile also asks for more, so this looks for the write rather than at everything.
    expect(pile().sent).toContainEqual({type: "update.email.archive", email_id: "m-1"});
    // Still drawn, and marked, so the stack can play it out.
    expect(viewModel.emails.map((email) => email.id)).toEqual(["m-1", "m-2"]);
    expect(viewModel.emails[0].classification).toEqual({type: "archive"});
    expect(viewModel.currentEmailId).toBe("m-2");
});

test("keeping a mail moves on and leaves it in the mailbox", async () => {
    const {viewModel, pile, serve} = stack();

    await serve(["m-1", "m-2"]);
    viewModel.onKeepEmail();

    expect(viewModel.currentEmailId).toBe("m-2");
    // Nothing moves the mail; the only thing the server hears is that it has been seen.
    expect(pile().sent.filter((message) => String(message.type).startsWith("update."))).toEqual([
        {type: "update.email.read", email_id: "m-1"},
    ]);
});

test("dealing with a card marks the mail read, and only the first time", async () => {
    const {viewModel, pile, content, serve} = stack();

    await serve(["m-1", "m-2"]);
    viewModel.onKeepEmail();

    expect(pile().sent).toContainEqual({type: "update.email.read", email_id: "m-1"});

    // It comes back read, so going back to that card and archiving it does not say so again.
    content().deliver({type: "data.emails", emails: [{...wireMail("m-1"), is_read: true}]});
    await settle();
    viewModel.onPreviousEmail();
    viewModel.onArchiveOrUnarchiveEmail();

    expect(pile().sent.filter((message) => message.type === "update.email.read")).toEqual([
        {type: "update.email.read", email_id: "m-1"},
    ]);
});

test("walking back and forth stays inside the pile", async () => {
    const {viewModel, serve} = stack();

    await serve(["m-1", "m-2"]);

    viewModel.onPreviousEmail();
    expect(viewModel.currentEmailId).toBe("m-1");

    viewModel.onNextEmail();
    viewModel.onNextEmail();
    expect(viewModel.currentEmailId).toBe("m-2");
});

test("the next batch is asked for as the pile runs low", async () => {
    const {viewModel, pile, serve} = stack();

    await serve(["m-1", "m-2", "m-3"]);
    viewModel.onNextEmail();

    // Fewer than MAX_EMAILS_BEFORE_REFETCH left below the current card.
    expect(pile().sent).toContainEqual({type: "request.emails"});
});

test("a mail whose metadata never arrives does not block the pile", async () => {
    const {viewModel, pile, content} = stack();

    pile().deliver({type: "data.emails", email_ids: ["gone", "m-2"]});
    await settle();
    content().deliver({type: "data.emails.unknown", ids: ["gone"]});
    content().deliver({type: "data.emails", emails: [wireMail("m-2")]});
    await settle();

    // The pick falls through to a card that is actually drawn.
    expect(viewModel.emails.map((email) => email.id)).toEqual(["m-2"]);
    expect(viewModel.currentEmailId).toBe("m-2");
});

test("disposing lets go of every mail", async () => {
    const {viewModel, content, serve} = stack();

    await serve(["m-1", "m-2"]);
    viewModel.dispose();
    await settle();

    // Their grace periods end on their own, so this collects them rather than expecting one
    // message with both.
    const released = content()
        .sent.filter((message) => message.type === "unsubscribe.emails")
        .flatMap((message) => message.ids as string[]);
    expect(released.sort()).toEqual(["m-1", "m-2"]);
});
