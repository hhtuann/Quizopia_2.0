import type { HTMLAttributes } from "react";

export type PageContainerProps = HTMLAttributes<HTMLDivElement> & {
  /** Public marketing content is bounded; application workspaces stay fluid. */
  width?: "full" | "marketing";
};

export function PageContainer({
  className = "",
  width = "full",
  ...props
}: PageContainerProps) {
  return (
    <div
      {...props}
      className={`mx-auto w-full px-4 sm:px-6 lg:px-8 ${width === "marketing" ? "max-w-7xl" : ""} ${className}`}
    />
  );
}
