import type { HTMLAttributes } from "react";

export type PageContainerProps = HTMLAttributes<HTMLDivElement>;

export function PageContainer({
  className = "",
  ...props
}: PageContainerProps) {
  return (
    <div
      {...props}
      className={`mx-auto w-full px-4 sm:px-6 lg:px-8 ${className}`}
    />
  );
}
