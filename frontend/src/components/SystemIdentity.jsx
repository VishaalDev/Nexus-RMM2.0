import Panel from "./Panel";
import SpecTable from "./SpecTable";
import { LoadingState } from "./States";
import { formatDateTime, formatUptime } from "../utils/format";

/**
 * Identity comes from two endpoints: /api/device carries the computer ID,
 * /api/device/hardware carries OS build, BIOS, architecture and boot time.
 * Each field falls back to the other source where both provide it, and any
 * field neither provides reads "Unavailable" rather than being invented.
 */
export default function SystemIdentity({ device, hardware }) {
  const dev = device.data;
  const sys = hardware.data?.system;

  if (!dev && !sys) {
    return (
      <Panel title="System identity">
        {device.error ? (
          <p className="state state-error" role="alert">
            System identity unavailable: {device.error.message}
          </p>
        ) : (
          <LoadingState>Loading system identity&hellip;</LoadingState>
        )}
      </Panel>
    );
  }

  const uptime = sys?.bootTime ? formatUptime(sys.bootTime) : null;
  const os = sys
    ? `${sys.osFamily} ${sys.osVersion}`
    : dev
      ? `${dev.osName} ${dev.osVersion}`
      : null;
  const bios = sys
    ? `${sys.biosVendor} ${sys.biosVersion}${sys.biosReleaseDate && sys.biosReleaseDate !== "Unknown" ? ` (${sys.biosReleaseDate})` : ""}`
    : null;

  const left = [
    ["Hostname", sys?.hostname ?? dev?.hostname],
    ["Computer ID", dev?.computerId, true],
    ["Manufacturer", sys?.manufacturer ?? dev?.manufacturer],
    ["Model", sys?.model ?? dev?.model],
    ["Baseboard", sys ? `${sys.baseboardManufacturer} ${sys.baseboardModel}` : null],
  ];
  const right = [
    ["Operating system", os],
    ["OS build", sys?.osBuild],
    ["Architecture", sys?.architecture],
    ["BIOS", bios],
    [
      "Boot time",
      sys?.bootTime ? `${formatDateTime(sys.bootTime)}${uptime ? ` (up ${uptime})` : ""}` : null,
    ],
  ];

  return (
    <Panel title="System identity">
      <div className="two-col">
        <SpecTable rows={left} caption="Machine identity" />
        <SpecTable rows={right} caption="Operating system and firmware" />
      </div>
      {hardware.error && !sys && (
        <p className="note">
          OS build, BIOS, architecture and boot time are unavailable: {hardware.error.message}
        </p>
      )}
    </Panel>
  );
}
