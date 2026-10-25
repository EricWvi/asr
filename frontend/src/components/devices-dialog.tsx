import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { LaptopIcon, SmartphoneIcon, XIcon } from "lucide-react";
import { Dialog } from "radix-ui";
import { useEffect, useRef, useState } from "react";

import { api, type Device } from "@/lib/api";
import { signedInOn, usedAgo } from "@/lib/dates";
import { notifyError } from "@/lib/toast";

/** The signed-in Android and Mac clients, with a way to revoke each. */
export function DevicesDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const devices = useQuery({
    queryKey: ["devices"],
    queryFn: api.devices,
    enabled: open,
    // Keeps the online state of Macs fresh while the dialog is open.
    refetchInterval: 10_000,
  });

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
            className="flex max-h-[min(560px,calc(100vh-64px))] w-[440px] flex-col rounded-[16px] bg-surface text-ink shadow-[inset_0_0_0_1px_var(--line),var(--shadow-lg)] outline-none"
          >
            <div className="flex items-center pt-3.5 pr-3 pb-1.5 pl-5">
              <Dialog.Title className="flex-1 text-[15px] font-semibold">设备</Dialog.Title>
              <Dialog.Close
                title="关闭"
                className="grid size-[30px] place-items-center rounded-[9px] text-ink-2 transition-colors hover:bg-selected hover:text-ink"
              >
                <XIcon className="size-4" strokeWidth={1.75} />
              </Dialog.Close>
            </div>
            <div className="overflow-y-auto px-2 pt-1 pb-2.5">
              {devices.data?.length === 0 && (
                <div className="pt-7 pb-6 text-center text-[13px] text-ink-3">还没有登录过的设备</div>
              )}
              {devices.data?.map((d) => <DeviceRow key={d.id} device={d} />)}
            </div>
          </Dialog.Content>
        </Dialog.Overlay>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function DeviceRow({ device }: { device: Device }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const timer = useRef<number>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);

  const revoke = useMutation({
    mutationFn: () => api.revokeDevice(device.id),
    onSuccess: () => queryClient.setQueryData<Device[]>(["devices"], (list) => list?.filter((d) => d.id !== device.id)),
    onError: (err) => notifyError(err, "吊销失败"),
  });

  // Revoking cannot be undone, so it takes a second click within 3 seconds.
  const onRevoke = () => {
    window.clearTimeout(timer.current);
    if (confirming) {
      setConfirming(false);
      revoke.mutate();
      return;
    }
    setConfirming(true);
    timer.current = window.setTimeout(() => setConfirming(false), 3000);
  };

  const Icon = device.platform === "mac" ? LaptopIcon : SmartphoneIcon;
  return (
    <div className="group flex h-14 items-center gap-3 rounded-item pr-2 pl-3 hover:bg-hover">
      <span className="grid size-8 flex-none place-items-center rounded-[9px] bg-chip text-ink-2">
        <Icon className="size-4" strokeWidth={1.75} />
      </span>
      <span className="min-w-0 flex-1">
        <div className="truncate text-sm">{device.name}</div>
        <div className="flex items-center gap-1.5 text-xs text-ink-2">
          {device.online ? (
            <>
              <span className="tint-mint size-1.5 rounded-full bg-tint-deep" />
              在线
            </>
          ) : (
            usedAgo(new Date(device.last_used_at))
          )}
          <span>·</span>
          {device.platform === "mac" ? "Mac" : "安卓"}
          <span>·</span>
          {signedInOn(new Date(device.created_at))}
        </div>
      </span>
      <button
        onClick={onRevoke}
        disabled={revoke.isPending}
        className={
          confirming
            ? "h-7 rounded-[7px] bg-rec px-2.5 text-[12.5px] text-white"
            : "h-7 rounded-[7px] px-2.5 text-[12.5px] text-ink-2 opacity-0 transition-[opacity,background-color,color] group-hover:opacity-100 hover:bg-selected hover:text-ink focus-visible:opacity-100"
        }
      >
        {confirming ? "确认吊销" : "吊销"}
      </button>
    </div>
  );
}
