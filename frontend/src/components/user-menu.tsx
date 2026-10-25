import { useQueryClient } from "@tanstack/react-query";
import { LogOutIcon, MonitorSmartphoneIcon } from "lucide-react";
import { useState } from "react";

import { DevicesDialog } from "@/components/devices-dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { logout, type User } from "@/lib/api";
import { useSession } from "@/lib/session";
import { notifyError } from "@/lib/toast";

export function UserMenu({ user }: { user: User }) {
  const queryClient = useQueryClient();
  const setSignedOut = useSession((s) => s.setSignedOut);
  const [devicesOpen, setDevicesOpen] = useState(false);
  const name = user.name || user.email;

  const signOut = async () => {
    try {
      await logout();
    } catch (err) {
      notifyError(err, "退出登录失败");
      return;
    }
    setSignedOut(true);
    queryClient.clear();
  };

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button className="mt-2 mb-3 flex items-center gap-2.5 rounded-item py-1.5 pr-1 pl-2 text-left transition-colors hover:bg-hover data-[state=open]:bg-hover">
            <span className="grid size-7 place-items-center rounded-full bg-periwinkle text-xs font-semibold text-[#1d1d1f]">
              {name.slice(0, 1).toUpperCase()}
            </span>
            <span className="flex-1 truncate text-[13px]">{name}</span>
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent side="top" align="start" className="min-w-[168px]">
          <DropdownMenuItem onSelect={() => setDevicesOpen(true)}>
            <MonitorSmartphoneIcon />
            设备
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={() => void signOut()}>
            <LogOutIcon />
            退出登录
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <DevicesDialog open={devicesOpen} onOpenChange={setDevicesOpen} />
    </>
  );
}
