import { createContext, useContext } from "react";

export const EMPTY_ADVISOR_STATE = { data: null, error: null, loading: false, refetch: () => {} };
export const AdvisorContext = createContext(EMPTY_ADVISOR_STATE);

/** Without a provider this returns "no data", so consumers render nothing rather than failing. */
export function useAdvisor() {
  return useContext(AdvisorContext);
}
