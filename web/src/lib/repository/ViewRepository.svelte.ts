import {
    parseView,
    ViewSocket,
    type View,
    type ViewGrouping,
    type ViewPayload,
    type ViewSorting,
} from "$lib/repository/ViewSocket";
import {reordered} from "$lib/app/views/reorder";

const CREATE_ENDPOINT = "/api/users/me/views/new";
const ITEM_ENDPOINT = (id: string) => `/api/users/me/views/${encodeURIComponent(id)}`;

/**
 * What one patch changes. Anything left out stays as it is -- that is the point of the route
 * behind it, so a rename does not carry a grouping along and overwrite what another tab set.
 */
export type ViewPatch = {
    name?: string;
    /** The whole settings object; there is nothing inside it to address on its own. */
    settings?: {groupings: ViewGrouping[]; sorting: ViewSorting};
    /** Where the view goes: behind [afterViewId], or at the top of the list when that is null. */
    position?: {afterViewId: string | null};
};

/**
 * The user's views, kept current by a socket.
 *
 * The socket is only up while something is actually watching: [connect] hands back the release,
 * and the last one to let go closes it. A component therefore does
 * `$effect(() => repositories.views.connect())` and is done -- same as [HomeScreenRepository].
 *
 * Writes go through the api and are not applied here: the server announces them and the list
 * comes back over the socket, so what is on screen is what the server has, and a change another
 * tab made arrives the same way as one this tab asked for. [move] is the one exception -- a
 * dragged row cannot wait for a round trip.
 */
export class ViewRepository {
    /** Every view, in sidebar order. Empty until the socket has said -- see [hasLoaded]. */
    views: View[] = $state([]);

    /** Whether the list below is the server's answer rather than the empty state before it. */
    hasLoaded: boolean = $state(false);

    private readonly socket: ViewSocket;

    /** Open [connect] handles. The socket lives exactly as long as there is one. */
    private watchers = 0;

    constructor(socket?: ViewSocket) {
        this.socket =
            socket ??
            new ViewSocket({
                onViews: (views) => {
                    this.views = views;
                    this.hasLoaded = true;
                },
            });
    }

    /** Starts watching; call the returned function to stop. Safe to nest. */
    connect(): () => void {
        this.watchers++;
        if (this.watchers === 1) this.socket.start();

        let released = false;
        return () => {
            // Guarded: an effect that re-runs must not release the same handle twice and close a
            // socket somebody else is still watching.
            if (released) return;
            released = true;
            this.watchers--;
            if (this.watchers === 0) this.socket.stop();
        };
    }

    /**
     * Adds an empty view and hands back what the server made of it -- the name is generated and
     * the caller did not choose it.
     *
     * The list is not touched here; it arrives over the socket a moment later.
     */
    async create(signal?: AbortSignal): Promise<View> {
        const response = await fetch(CREATE_ENDPOINT, {
            method: "POST",
            credentials: "include",
            signal,
        });
        if (!response.ok) throw new Error(`Could not create the view: ${response.status}`);

        // The answer is one view in the same shape the socket sends, so it is read the same way.
        return parseView((await response.json()) as ViewPayload);
    }

    /**
     * Changes single attributes of a view. What is not in [patch] is not touched.
     *
     * The list is not written here either: the server announces the change and the socket sends
     * it back. [move] is the exception, and says why.
     */
    async update(id: string, patch: ViewPatch, signal?: AbortSignal): Promise<View> {
        const response = await fetch(ITEM_ENDPOINT(id), {
            method: "PATCH",
            credentials: "include",
            headers: {"content-type": "application/json"},
            body: JSON.stringify(toBody(patch)),
            signal,
        });
        if (!response.ok) throw new Error(`Could not change the view: ${response.status}`);

        return parseView((await response.json()) as ViewPayload);
    }

    /**
     * Puts [id] behind [afterViewId], or at the top when that is null.
     *
     * Applied here first and then sent: a dragged row that snaps back to where it came from for
     * the length of a request reads as a failed drag. The socket's answer overwrites this a
     * moment later -- and if the request fails, so does the order it was applied for.
     */
    async move(id: string, afterViewId: string | null): Promise<void> {
        const before = this.views;

        const order = reordered(
            before.map((view) => view.id),
            id,
            afterViewId
        );
        this.views = order.map((viewId) => before.find((view) => view.id === viewId)!);

        try {
            await this.update(id, {position: {afterViewId}});
        } catch (error) {
            this.views = before;
            throw error;
        }
    }
}

function toBody(patch: ViewPatch) {
    return {
        ...(patch.name === undefined ? {} : {name: patch.name}),
        ...(patch.settings === undefined
            ? {}
            : {
                  view: {
                      groupings: patch.settings.groupings.map((grouping) => ({
                          type: grouping.kind,
                          sort_reversed: grouping.reversed,
                      })),
                      email_sorting: {
                          type: patch.settings.sorting.kind,
                          sort_reversed: patch.settings.sorting.reversed,
                      },
                  },
              }),
        // Present with a null inside is a move to the top; absent is no move at all.
        ...(patch.position === undefined
            ? {}
            : {position: {after_view_id: patch.position.afterViewId}}),
    };
}
