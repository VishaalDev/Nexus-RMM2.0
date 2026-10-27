import Panel from "./Panel";
import SpecTable from "./SpecTable";
import { LoadingState } from "./States";
import { formatDateTime } from "../utils/format";
import { describeSystem } from "../utils/system";

export default function SystemIdentity({ device, hardware }) {
  const { dev, sys, uptime, os, bios } = describeSystem(device, hardware);

  if (!dev && !sys) {
    return (
      <Panel title="System">
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

  const make = [sys?.manufacturer ?? dev?.manufacturer, sys?.model ?? dev?.model].filter(Boolean).join(" ");
  const rows = [
    ["Hostname", sys?.hostname ?? dev?.hostname],
    ["Hardware", make || null],
    ["Baseboard", sys ? `${sys.baseboardManufacturer} ${sys.baseboardModel}` : null],
    ["Operating system", os],
    ["OS build", sys?.osBuild],
    ["Architecture", sys?.architecture],
    ["BIOS", bios],
    ["Boot time", sys?.bootTime ? `${formatDateTime(sys.bootTime)}${uptime ? ` (up ${uptime})` : ""}` : null],
    ["Computer ID", dev?.computerId, true],
  ];

  return (
    <Panel title="System">
      <SpecTable rows={rows} caption="System identity" />
      {hardware.error && !sys && (
        <p className="note">OS build, BIOS, architecture and boot time are unavailable: {hardware.error.message}</p>
      )}
    </Panel>
  );
}
