import { useCallback, useEffect, useState } from "react";

/**
 * Minimal hash-based navigation (no router dependency): the active page
 * survives a browser refresh and the back button, and pages are linkable.
 */
export function useHashRoute(validRoutes, defaultRoute) {
  const read = useCallback(() => {
    const hash = window.location.hash.replace(/^#\/?/, "");
    return validRoutes.includes(hash) ? hash : defaultRoute;
  }, [validRoutes, defaultRoute]);

  const [route, setRoute] = useState(read);

  useEffect(() => {
    const onHashChange = () => setRoute(read());
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, [read]);

  const navigate = useCallback((next) => {
    window.location.hash = `/${next}`;
  }, []);

  return [route, navigate];
}
