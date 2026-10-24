import { StateEffect, StateField } from "@codemirror/state";
import { Decoration, EditorView, WidgetType } from "@codemirror/view";

import { api } from "@/lib/api";
import { notify, notifyError } from "@/lib/toast";

/*
 * Pasting or dropping images uploads them and then inserts `![](url)`.
 * While uploading, a faded preview sits where the image will go; nothing
 * enters the text until the upload succeeds, so a failure leaves no trace.
 */

const MAX_SIZE = 10 << 20;
const TYPES = ["image/png", "image/jpeg", "image/gif", "image/webp"];

type Pending = { id: number; pos: number; preview: string };

const addPending = StateEffect.define<Pending>();
const removePending = StateEffect.define<number>();

class PendingWidget extends WidgetType {
  constructor(readonly preview: string) {
    super();
  }
  eq(other: PendingWidget) {
    return other.preview == this.preview;
  }
  toDOM() {
    const wrap = document.createElement("div");
    wrap.className = "cm-md-img cm-md-img-pending";
    const img = document.createElement("img");
    img.src = this.preview;
    wrap.append(img);
    return wrap;
  }
}

/** Uploads in flight, with where each will be inserted, kept in step with edits. */
const pendingField = StateField.define<Pending[]>({
  create: () => [],
  update(list, tr) {
    if (tr.docChanged) list = list.map((p) => ({ ...p, pos: tr.changes.mapPos(p.pos) }));
    for (const e of tr.effects) {
      if (e.is(addPending)) list = [...list, e.value];
      if (e.is(removePending)) list = list.filter((p) => p.id != e.value);
    }
    return list;
  },
  provide: (f) =>
    EditorView.decorations.compute([f], (state) =>
      Decoration.set(
        state
          .field(f)
          .map((p) =>
            Decoration.widget({ widget: new PendingWidget(p.preview), block: true, side: 1 }).range(
              state.doc.lineAt(p.pos).to,
            ),
          ),
        true,
      ),
    ),
});

let seq = 0;

function upload(view: EditorView, files: File[], pos: number) {
  const images = files.filter((f) => f.type.startsWith("image/"));
  if (!images.length) return false;
  for (const file of images) {
    if (!TYPES.includes(file.type)) {
      notify("只支持 PNG、JPEG、GIF 和 WebP 图片");
      continue;
    }
    if (file.size > MAX_SIZE) {
      notify("图片不能超过 10 MB");
      continue;
    }
    const id = ++seq;
    const preview = URL.createObjectURL(file);
    view.dispatch({ effects: addPending.of({ id, pos, preview }) });
    api.uploadImage(file).then(
      ({ url }) => {
        // The editor may be gone after switching documents.
        if (!view.dom.isConnected) return;
        const at = view.state.field(pendingField).find((p) => p.id == id)?.pos;
        if (at === undefined) return;
        const line = view.state.doc.lineAt(at);
        // The image goes on a line of its own.
        const before = at > line.from ? "\n" : "";
        const after = at < line.to ? "\n" : "";
        const insert = `${before}![](${url})${after}`;
        view.dispatch({
          changes: { from: at, insert },
          effects: removePending.of(id),
          selection: { anchor: at + insert.length - after.length },
          userEvent: "input.paste",
        });
        URL.revokeObjectURL(preview);
      },
      (err) => {
        if (view.dom.isConnected) view.dispatch({ effects: removePending.of(id) });
        URL.revokeObjectURL(preview);
        notifyError(err, "图片上传失败");
      },
    );
  }
  return true;
}

const handlers = EditorView.domEventHandlers({
  paste(e, view) {
    const files = [...(e.clipboardData?.files ?? [])];
    if (!files.some((f) => f.type.startsWith("image/"))) return false;
    e.preventDefault();
    return upload(view, files, view.state.selection.main.head);
  },
  drop(e, view) {
    const files = [...(e.dataTransfer?.files ?? [])];
    if (!files.some((f) => f.type.startsWith("image/"))) return false;
    e.preventDefault();
    const pos = view.posAtCoords({ x: e.clientX, y: e.clientY }) ?? view.state.selection.main.head;
    return upload(view, files, pos);
  },
});

export const imageUpload = [pendingField, handlers];
