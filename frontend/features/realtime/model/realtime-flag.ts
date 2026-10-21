/**
 * Realtime behavior is default OFF for every build and deployment. Activation requires an
 * explicit build-time flag supplied by the deployment, never a code change or fallback.
 */
export function realtimeFeatureEnabled(): boolean {
  return process.env.NEXT_PUBLIC_REALTIME_ENABLED === "true";
}
