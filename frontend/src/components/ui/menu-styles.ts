/*
 * One look for every menu (dropdown and context menu), after the sidebar
 * menu in design/pc.html: padded rounded items on a surface card.
 */
export const menuContent =
  "z-50 min-w-[168px] overflow-hidden rounded-[12px] bg-surface p-[5px] text-ink shadow-[inset_0_0_0_1px_var(--line),var(--shadow-lg)] data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:animate-in data-[state=open]:fade-in-0";

export const menuItem =
  "relative flex h-8 cursor-default items-center gap-2.5 rounded-[7px] px-2.5 text-[13px] outline-hidden select-none data-[highlighted]:bg-hover data-[disabled]:pointer-events-none data-[disabled]:opacity-50 [&_svg]:pointer-events-none [&_svg]:size-[15px] [&_svg]:shrink-0 [&_svg]:text-ink-2 data-[variant=danger]:text-rec data-[variant=danger]:data-[highlighted]:bg-rec/10 data-[variant=danger]:[&_svg]:text-rec";

export const menuSeparator = "mx-1 my-[5px] h-px bg-line";
