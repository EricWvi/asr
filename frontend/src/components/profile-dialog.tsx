import { useMutation, useQueryClient } from "@tanstack/react-query";
import { CameraIcon, ImageIcon, LoaderCircleIcon, XIcon } from "lucide-react";
import { Dialog } from "radix-ui";
import { useEffect, useRef, useState } from "react";

import { Avatar } from "@/components/avatar";
import { api, type User } from "@/lib/api";
import { notifyError } from "@/lib/toast";
import { cn } from "@/lib/utils";

const TYPES = ["image/png", "image/jpeg", "image/gif", "image/webp"];
const btn = "inline-flex h-8 items-center gap-1.5 rounded-[8px] px-3.5 text-[13px] font-medium transition-[background-color,opacity]";
const errorText = "text-[12.5px] text-rec";

/**
 * Name and email come from the identity provider; only the avatar can be
 * changed. A picked image is cropped to a circle in the same dialog and
 * uploaded as a 256×256 square.
 */
export function ProfileDialog({
  user,
  open,
  onOpenChange,
}: {
  user: User;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const file = useRef<HTMLInputElement>(null);
  const [picked, setPicked] = useState<string | null>(null);
  const [error, setError] = useState("");

  // Opening starts over.
  useEffect(() => {
    if (open) return;
    setPicked(null);
    setError("");
  }, [open]);
  useEffect(() => () => void (picked && URL.revokeObjectURL(picked)), [picked]);

  const setAvatarURL = (avatar_url: string) => queryClient.setQueryData<User>(["me"], (u) => u && { ...u, avatar_url });
  const remove = useMutation({
    mutationFn: api.deleteAvatar,
    onSuccess: () => setAvatarURL(""),
    onError: (err) => notifyError(err, "移除头像失败"),
  });

  const pick = () => {
    setError("");
    file.current?.click();
  };
  const onFile = (f: File | undefined) => {
    if (!f) return;
    if (!TYPES.includes(f.type)) {
      setError("只支持 PNG、JPEG、GIF 和 WebP 图片");
      return;
    }
    setError("");
    setPicked(URL.createObjectURL(f));
  };

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 grid place-items-center bg-black/18 data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:animate-in data-[state=open]:fade-in-0 dark:bg-black/45">
          <Dialog.Content
            aria-describedby={undefined}
            // Opened from a menu with the pointer: no focus ring on the close button.
            onOpenAutoFocus={(e) => {
              e.preventDefault();
              (e.currentTarget as HTMLElement).focus();
            }}
            className="flex w-[360px] flex-col rounded-[16px] bg-surface text-ink shadow-[inset_0_0_0_1px_var(--line),var(--shadow-lg)] outline-none"
          >
            <div className="flex items-center pt-3.5 pr-3 pb-1.5 pl-5">
              <Dialog.Title className="flex-1 text-[15px] font-semibold">{picked ? "裁剪头像" : "个人资料"}</Dialog.Title>
              <Dialog.Close
                title="关闭"
                className="grid size-[30px] place-items-center rounded-[9px] text-ink-2 transition-colors hover:bg-selected hover:text-ink"
              >
                <XIcon className="size-4" strokeWidth={1.75} />
              </Dialog.Close>
            </div>
            {picked ? (
              <Cropper
                src={picked}
                onCancel={() => setPicked(null)}
                onSaved={(url) => {
                  setAvatarURL(url);
                  setPicked(null);
                }}
              />
            ) : (
              <div className="flex flex-col items-center px-6 pt-2 pb-6">
                <button title="更换头像" className="group relative size-[88px] rounded-full" onClick={pick}>
                  <Avatar user={user} className="size-full text-[34px]" />
                  <span className="absolute inset-0 grid place-items-center rounded-full bg-black/38 text-white opacity-0 transition-opacity group-hover:opacity-100">
                    <CameraIcon className="size-4" strokeWidth={1.75} />
                  </span>
                </button>
                <div className="mt-3.5 text-base font-semibold">{user.name || user.email}</div>
                {user.name && user.email && <div className="mt-[3px] text-[13px] text-ink-2">{user.email}</div>}
                <div className="mt-5 flex gap-2">
                  <button className={cn(btn, "bg-chip hover:bg-selected")} onClick={pick}>
                    更换头像
                  </button>
                  {user.avatar_url && (
                    <button
                      className={cn(btn, "text-ink-2 hover:bg-hover hover:text-ink disabled:opacity-55")}
                      disabled={remove.isPending}
                      onClick={() => remove.mutate()}
                    >
                      移除头像
                    </button>
                  )}
                </div>
                {error && <div className={cn(errorText, "mt-3")}>{error}</div>}
                <input
                  ref={file}
                  type="file"
                  accept={TYPES.join(",")}
                  hidden
                  onChange={(e) => {
                    onFile(e.target.files?.[0]);
                    e.target.value = "";
                  }}
                />
              </div>
            )}
          </Dialog.Content>
        </Dialog.Overlay>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

const STAGE_W = 320;
const STAGE_H = 280;
const RING = 240;
const OUT = 256;
const MAX_ZOOM = 4;

/** Where the image sits: x, y are its top-left corner in the stage, scale is base × zoom. */
type View = { zoom: number; x: number; y: number };

/**
 * The image under a circular frame: drag to move it, slider or wheel to
 * zoom. It always covers the circle.
 */
function Cropper({ src, onCancel, onSaved }: { src: string; onCancel: () => void; onSaved: (url: string) => void }) {
  const img = useRef<HTMLImageElement>(null);
  const stage = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState<{ w: number; h: number } | null>(null);
  const [view, setView] = useState<View>({ zoom: 1, x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState("");
  const base = size ? RING / Math.min(size.w, size.h) : 1;

  const clamp = (v: View): View => {
    if (!size) return v;
    const k = base * v.zoom;
    const x = Math.min((STAGE_W - RING) / 2, Math.max((STAGE_W + RING) / 2 - size.w * k, v.x));
    const y = Math.min((STAGE_H - RING) / 2, Math.max((STAGE_H + RING) / 2 - size.h * k, v.y));
    return { zoom: v.zoom, x, y };
  };
  // Zooms keeping the stage point (cx, cy) still.
  const zoomTo = (v: View, zoom: number, cx = STAGE_W / 2, cy = STAGE_H / 2): View => {
    zoom = Math.min(MAX_ZOOM, Math.max(1, zoom));
    const r = zoom / v.zoom;
    return clamp({ zoom, x: cx - (cx - v.x) * r, y: cy - (cy - v.y) * r });
  };

  // React wheel listeners are passive and cannot keep the page from scrolling.
  const zoomRef = useRef(zoomTo);
  zoomRef.current = zoomTo;
  useEffect(() => {
    const el = stage.current;
    if (!el) return;
    const wheel = (e: WheelEvent) => {
      e.preventDefault();
      const r = el.getBoundingClientRect();
      setView((v) => zoomRef.current(v, v.zoom * Math.exp(-e.deltaY / 300), e.clientX - r.left, e.clientY - r.top));
    };
    el.addEventListener("wheel", wheel, { passive: false });
    return () => el.removeEventListener("wheel", wheel);
  }, []);

  const save = useMutation({
    mutationFn: async () => {
      const el = img.current;
      if (!el || !size) throw new Error("图片还没有加载完");
      const k = base * view.zoom;
      const canvas = document.createElement("canvas");
      canvas.width = canvas.height = OUT;
      const ctx = canvas.getContext("2d");
      if (!ctx) throw new Error("浏览器无法处理图片");
      ctx.imageSmoothingQuality = "high";
      const side = RING / k;
      ctx.drawImage(el, ((STAGE_W - RING) / 2 - view.x) / k, ((STAGE_H - RING) / 2 - view.y) / k, side, side, 0, 0, OUT, OUT);
      // Browsers without WebP encoding hand back a PNG, which is fine too.
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/webp", 0.9));
      if (!blob) throw new Error("浏览器无法处理图片");
      return api.setAvatar(blob);
    },
    onSuccess: (r) => onSaved(r.avatar_url),
    onError: (err) => setError(err instanceof Error && err.message ? err.message : "保存失败，请重试"),
  });

  const k = base * view.zoom;
  return (
    <div className="px-5 pt-1.5 pb-5">
      <div
        ref={stage}
        className={cn(
          "relative touch-none overflow-hidden rounded-[12px] bg-[#1d1d1f] select-none",
          dragging ? "cursor-grabbing" : "cursor-grab",
        )}
        style={{ width: STAGE_W, height: STAGE_H }}
        onPointerDown={(e) => {
          if (!size) return;
          e.currentTarget.setPointerCapture(e.pointerId);
          setDragging(true);
          const x0 = e.clientX - view.x;
          const y0 = e.clientY - view.y;
          const el = e.currentTarget;
          el.onpointermove = (m) => setView((v) => clamp({ ...v, x: m.clientX - x0, y: m.clientY - y0 }));
          el.onpointerup = el.onpointercancel = () => {
            el.onpointermove = null;
            setDragging(false);
          };
        }}
      >
        <img
          ref={img}
          src={src}
          alt=""
          className="pointer-events-none absolute top-0 left-0 max-w-none origin-top-left"
          style={{ transform: `translate(${view.x}px, ${view.y}px) scale(${k})`, visibility: size ? "visible" : "hidden" }}
          onLoad={(e) => {
            const { naturalWidth: w, naturalHeight: h } = e.currentTarget;
            const b = RING / Math.min(w, h);
            setSize({ w, h });
            setView({ zoom: 1, x: (STAGE_W - w * b) / 2, y: (STAGE_H - h * b) / 2 });
          }}
          onError={() => setError("无法打开这张图片")}
        />
        <div
          className="pointer-events-none absolute top-1/2 left-1/2 rounded-full shadow-[0_0_0_1.5px_rgb(255_255_255/0.85),0_0_0_999px_rgb(0_0_0/0.62)]"
          style={{ width: RING, height: RING, margin: -RING / 2 }}
        />
      </div>
      <div className="mx-1 mt-4 flex items-center gap-2.5 text-ink-3">
        <ImageIcon className="size-3.5 flex-none" strokeWidth={1.75} />
        <input
          type="range"
          aria-label="缩放"
          min={1}
          max={MAX_ZOOM}
          step={0.01}
          value={view.zoom}
          className="flex-1 accent-ink"
          onChange={(e) => setView((v) => zoomTo(v, Number(e.target.value)))}
        />
        <ImageIcon className="size-[18px] flex-none" strokeWidth={1.75} />
      </div>
      <div className="mt-[18px] flex items-center justify-end gap-2">
        {error && <span className={cn(errorText, "mr-auto")}>{error}</span>}
        <button className={cn(btn, "text-ink-2 hover:bg-hover hover:text-ink")} onClick={onCancel}>
          取消
        </button>
        <button
          className={cn(btn, "bg-ink text-surface hover:opacity-88 disabled:opacity-55")}
          disabled={!size || save.isPending}
          onClick={() => {
            setError("");
            save.mutate();
          }}
        >
          {save.isPending && <LoaderCircleIcon className="size-3.5 animate-spin" strokeWidth={2} />}
          {save.isPending ? "保存中" : "保存"}
        </button>
      </div>
    </div>
  );
}
