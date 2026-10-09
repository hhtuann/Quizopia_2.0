import type { SVGProps } from "react";

type IconProps = SVGProps<SVGSVGElement>;

function IconFrame({ children, ...props }: IconProps) {
  return (
    <svg
      aria-hidden="true"
      fill="none"
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth="2"
      viewBox="0 0 24 24"
      {...props}
    >
      {children}
    </svg>
  );
}

export function ArrowRightIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M5 12h14m-7-7 7 7-7 7" />
    </IconFrame>
  );
}

export function ArrowUpRightIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M7 17 17 7M7 7h10v10" />
    </IconFrame>
  );
}

export function BookOpenIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="M12 7v14M3 18V5a2 2 0 0 1 2-2h2a5 5 0 0 1 5 5 5 5 0 0 1 5-5h2a2 2 0 0 1 2 2v13a1 1 0 0 1-1 1h-3a5 5 0 0 0-5 2 5 5 0 0 0-5-2H4a1 1 0 0 1-1-1Z" />
    </IconFrame>
  );
}

export function PencilIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="m16 5 3 3M4 20l4.5-1 11-11a2.12 2.12 0 0 0-3-3l-11 11L4 20Z" />
    </IconFrame>
  );
}

export function CheckCircleIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <circle cx="12" cy="12" r="10" />
      <path d="m8 12 3 3 5-6" />
    </IconFrame>
  );
}

export function LayersIcon(props: IconProps) {
  return (
    <IconFrame {...props}>
      <path d="m12 2 9 5-9 5-9-5 9-5Zm-9 10 9 5 9-5M3 17l9 5 9-5" />
    </IconFrame>
  );
}
