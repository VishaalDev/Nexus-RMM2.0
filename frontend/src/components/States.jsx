export function LoadingState({ children = "Loading\u2026" }) {
  return (
    <p className="state" role="status">
      {children}
    </p>
  );
}

export function EmptyState({ children }) {
  return <p className="state">{children}</p>;
}

/** Actionable, never vague: says what failed and that it is being retried. */
export function ErrorNotice({ error, what }) {
  return (
    <p className="state state-error" role="alert">
      Could not load {what}. {error?.message ?? "Unknown error."} The request is retried
      automatically.
    </p>
  );
}

export function StaleNotice({ error, what }) {
  return (
    <p className="state state-warning" role="alert">
      Showing the last successful {what}. The latest refresh failed: {error?.message}
    </p>
  );
}

export function BackendOffline() {
  return (
    <div className="state-centered">
      <p className="state-error" role="alert">
        Backend unavailable. Start the backend service on port 8080 (
        <code>mvn spring-boot:run</code> from <code>backend/</code>) and this page will
        reconnect automatically.
      </p>
    </div>
  );
}
