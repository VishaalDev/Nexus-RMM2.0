import { formatUptime } from "./format";

/**
 * Identity comes from two endpoints: /api/device carries the computer ID,
 * /api/device/hardware carries OS build, BIOS, architecture and boot time.
 * Each field falls back to the other source where both provide it, and any
 * field neither provides reads "Unavailable" rather than being invented.
 */
export function describeSystem(device, hardware) {
  const dev = device.data;
  const sys = hardware.data?.system;
  const uptime = sys?.bootTime ? formatUptime(sys.bootTime) : null;
  const os = sys ? `${sys.osFamily} ${sys.osVersion}` : dev ? `${dev.osName} ${dev.osVersion}` : null;
  const bios = sys
    ? `${sys.biosVendor} ${sys.biosVersion}${sys.biosReleaseDate && sys.biosReleaseDate !== "Unknown" ? ` (${sys.biosReleaseDate})` : ""}`
    : null;
  return { dev, sys, uptime, os, bios };
}
