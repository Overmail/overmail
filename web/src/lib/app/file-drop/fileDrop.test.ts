import {describe, expect, it} from "bun:test";
import {groupByKind, kindOfFile, previewDrag} from "./fileDrop";

function transfer(...types: string[]): DataTransfer {
    return {items: types.map((type) => ({kind: "file", type}))} as unknown as DataTransfer;
}

describe("kindOfFile", () => {
    it("tells an eml by its name, whatever the browser guessed", () => {
        expect(kindOfFile(new File([""], "Invoice.EML"))).toBe("eml");
        expect(kindOfFile(new File([""], "mail", {type: "message/rfc822"}))).toBe("eml");
        expect(kindOfFile(new File([""], "photo.png", {type: "image/png"}))).toBeNull();
    });
});

describe("previewDrag", () => {
    it("names the kind when every file is of it", () => {
        expect(previewDrag(transfer("message/rfc822", "message/rfc822"))).toEqual({type: "kind", kind: "eml", count: 2});
    });

    it("cannot tell a file without a type", () => {
        expect(previewDrag(transfer("message/rfc822", ""))).toEqual({type: "unknown", count: 2});
    });

    it("is unknown for a mix of supported and unsupported files", () => {
        expect(previewDrag(transfer("message/rfc822", "image/png"))).toEqual({type: "unknown", count: 2});
    });

    it("is unsupported when no file is of any kind", () => {
        expect(previewDrag(transfer("image/png"))).toEqual({type: "unsupported", count: 1});
    });
});

describe("groupByKind", () => {
    it("leaves out files of no kind", () => {
        const eml = new File([""], "a.eml");
        expect(groupByKind([eml, new File([""], "b.txt")])).toEqual(new Map([["eml", [eml]]]));
    });
});
