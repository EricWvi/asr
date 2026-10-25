import type { User } from "@/lib/api";
import { cn } from "@/lib/utils";

/** The user's avatar, or the first letter of their name on periwinkle. Size and text size come from className. */
export function Avatar({ user, className }: { user: User; className?: string }) {
  const name = user.name || user.email;
  return (
    <span
      className={cn(
        "grid flex-none place-items-center overflow-hidden rounded-full bg-periwinkle font-semibold text-[#1d1d1f]",
        className,
      )}
    >
      {user.avatar_url ? (
        <img src={user.avatar_url} alt="" className="size-full object-cover" />
      ) : (
        name.slice(0, 1).toUpperCase()
      )}
    </span>
  );
}
