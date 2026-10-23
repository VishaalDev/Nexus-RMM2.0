import { useEffect, useRef, useState, useCallback } from "react";

/**
 * Polls `fetchFn` every `intervalMs`. Guarantees:
 *  - no overlapping requests (a slow response is not raced by the next tick)
 *  - no state updates after unmount
 *  - explicit loading/error/data states so every page can render a real
 *    empty/error state instead of guessing
 */
export function usePolling(fetchFn, intervalMs, deps = []) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  const inFlight = useRef(false);
  const mounted = useRef(true);

  const runOnce = useCallback(async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    try {
      const result = await fetchFn();
      if (mounted.current) {
        setData(result);
        setError(null);
      }
    } catch (err) {
      if (mounted.current) setError(err);
    } finally {
      inFlight.current = false;
      if (mounted.current) setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    mounted.current = true;
    setLoading(true);
    runOnce();
    const id = setInterval(runOnce, intervalMs);
    return () => {
      mounted.current = false;
      clearInterval(id);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runOnce, intervalMs]);

  return { data, error, loading, refetch: runOnce };
}
