import { ErrorNotice, LoadingState, StaleNotice } from "./States";

/**
 * Renders the right state for a polled resource so no page hand-rolls
 * loading/error handling:
 *   - no data yet + loading  -> loading state
 *   - no data + error        -> actionable error
 *   - data + failed refresh  -> the data, with a stale warning above it
 */
export default function DataBoundary({ state, what, children }) {
  const { data, error, loading } = state;

  if (data === null || data === undefined) {
    if (error) return <ErrorNotice error={error} what={what} />;
    if (loading) return <LoadingState>Loading {what}&hellip;</LoadingState>;
    return null;
  }

  return (
    <>
      {error && <StaleNotice error={error} what={what} />}
      {children(data)}
    </>
  );
}
