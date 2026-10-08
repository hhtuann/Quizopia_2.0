interface AuthPageHeaderProps {
  readonly description: string;
  readonly title: string;
}

export function AuthPageHeader({ description, title }: AuthPageHeaderProps) {
  return (
    <header>
      <p className="text-xs font-bold uppercase tracking-[0.14em] text-primary">
        Quizopia account
      </p>
      <h1 className="mt-3 font-heading text-3xl font-extrabold tracking-[-0.035em] text-foreground">
        {title}
      </h1>
      <p className="mt-3 text-base leading-7 text-foreground-secondary">
        {description}
      </p>
    </header>
  );
}
