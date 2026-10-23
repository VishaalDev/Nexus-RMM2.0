import DataBoundary from "../components/DataBoundary";
import DataTable from "../components/DataTable";
import NetworkAdaptersTable from "../components/NetworkAdaptersTable";
import Panel from "../components/Panel";
import SpecTable from "../components/SpecTable";
import StatusBadge from "../components/StatusBadge";
import { useCurrentMetrics, useHardwareSnapshot } from "../hooks/useApiData";
import { formatBytes, formatFrequency, formatMegahertz } from "../utils/format";
import { findReadingByInstance, readingsOfType } from "../utils/metrics";

/**
 * Battery capacity units as the backend reports them (the string form of
 * the OS library's capacity-units value). Known units get a symbol;
 * anything else is shown verbatim rather than guessed at.
 */
const CAPACITY_UNIT_LABELS = {
  MWH: "mWh (milliwatt-hours)",
  MAH: "mAh (milliamp-hours)",
  RELATIVE: "relative units (not a physical unit)",
};

function describeCapacity(source) {
  const unit = source.capacityUnit;
  if (!unit || unit === "UNKNOWN") return null;
  return `${source.designCapacity} (design) / ${source.maxCapacity} (current maximum) \u2014 ${CAPACITY_UNIT_LABELS[unit] ?? unit}`;
}

const GPU_UNSUPPORTED_HEADLINE = "Unsupported \u2014 real-time GPU telemetry not integrated";

function CpuPanel({ cpu }) {
  const rows = [
    ["Name", cpu.name],
    ["Vendor", cpu.vendor],
    ["Identifier", cpu.identifier, true],
    ["Physical cores", cpu.physicalCores],
    ["Logical processors", cpu.logicalCores],
    ["Maximum frequency", formatFrequency(cpu.maxFreqHz)],
  ];
  return (
    <Panel title="CPU">
      <SpecTable rows={rows} caption="CPU specification" />
    </Panel>
  );
}

function MemoryPanel({ memory }) {
  const columns = [
    { key: "bank", header: "Bank", render: (m) => m.bankLabel },
    { key: "capacity", header: "Capacity", align: "right", mono: true, render: (m) => formatBytes(m.capacityBytes) },
    { key: "speed", header: "Speed", align: "right", mono: true, render: (m) => formatMegahertz(m.speedMHz) },
    { key: "type", header: "Type", render: (m) => m.memoryType },
    { key: "manufacturer", header: "Manufacturer", render: (m) => m.manufacturer },
  ];
  return (
    <Panel title="Memory" actions={`Total: ${formatBytes(memory.totalBytes)}`}>
      <DataTable
        caption="Installed memory modules"
        columns={columns}
        rows={memory.modules}
        getRowKey={(m, i) => `${m.bankLabel}|${i}`}
        emptyMessage="No individual memory modules were reported by this platform."
      />
    </Panel>
  );
}

function StorageDevicesPanel({ devices }) {
  const columns = [
    { key: "name", header: "Device", mono: true, wrap: "anywhere", minWidth: "10rem", render: (d) => d.name },
    { key: "model", header: "Model", wrap: "words", minWidth: "9rem", render: (d) => d.model },
    { key: "size", header: "Size", align: "right", mono: true, nowrap: true, render: (d) => formatBytes(d.sizeBytes) },
  ];
  return (
    <Panel title="Storage devices" actions="Live capacity and throughput: see the Storage page">
      <DataTable
        caption="Physical storage devices"
        columns={columns}
        rows={devices}
        getRowKey={(d) => d.name}
        emptyMessage="No physical disks were reported by this system."
      />
      {devices.length > 0 && (
        <p className="note">
          Inventory only. SMART and NVMe drive-health attributes are not collected by this build, so
          drive health cannot be assessed.
        </p>
      )}
    </Panel>
  );
}

function GpuPanel({ gpus, current }) {
  const util = readingsOfType(current, "GPU_UTILIZATION");
  const temp = readingsOfType(current, "GPU_TEMPERATURE");
  const genericDiagnostic = util[0]?.diagnostic ?? temp[0]?.diagnostic;

  if (gpus.length === 0) {
    return (
      <Panel title="GPU">
        <p className="muted">No display adapters were detected.</p>
        {genericDiagnostic && <p className="note">{genericDiagnostic}</p>}
      </Panel>
    );
  }

  return (
    <Panel title="GPU">
      <div className="stack">
        {gpus.map((gpu) => {
          const reading = findReadingByInstance(util, gpu.name) ?? findReadingByInstance(temp, gpu.name);
          return (
            <div key={gpu.name}>
              <SpecTable
                caption={`GPU: ${gpu.name}`}
                rows={[
                  ["Name", gpu.name],
                  ["Vendor", gpu.vendor],
                  ["VRAM", gpu.vramBytes > 0 ? formatBytes(gpu.vramBytes) : null],
                  [
                    "Utilization / temperature",
                    <span key="cap">
                      <StatusBadge status="UNSUPPORTED" label={GPU_UNSUPPORTED_HEADLINE} />
                    </span>,
                  ],
                ]}
              />
              <p className="note">{reading?.diagnostic ?? genericDiagnostic}</p>
            </div>
          );
        })}
      </div>
    </Panel>
  );
}

function BatteryPanel({ battery }) {
  if (!battery.present) {
    return (
      <Panel title="Battery">
        <p className="muted">
          No battery hardware detected &mdash; this system appears to be a desktop, or the
          platform does not expose a power source.
        </p>
      </Panel>
    );
  }

  return (
    <Panel title="Battery">
      <div className="stack">
        {battery.sources.map((source) => (
          <SpecTable
            key={source.name}
            caption={`Battery: ${source.name}`}
            rows={[
              ["Name", source.name],
              ["Charge level (remaining capacity)", `${source.remainingCapacityPercent.toFixed(1)}%`],
              ["Design / maximum capacity", describeCapacity(source)],
              ["Charging", source.charging ? "Yes" : "No"],
              ["On AC power", source.powerOnLine ? "Yes" : "No"],
            ]}
          />
        ))}
        <p className="note">
          Hardware inventory only, as reported by the operating system. &ldquo;Charge level&rdquo;
          is how full the battery is right now, not how healthy it is. Battery wear and health are
          not assessed here: no wear percentage is calculated, and the Health page&rsquo;s Battery
          score reflects charge-level usage patterns only.
        </p>
      </div>
    </Panel>
  );
}

export default function Hardware() {
  const hardware = useHardwareSnapshot();
  const current = useCurrentMetrics();

  return (
    <div className="page">
      <header className="page-header">
        <h2>Hardware DNA</h2>
        <p>Static specifications collected from this machine.</p>
      </header>

      <DataBoundary state={hardware} what="hardware inventory">
        {(data) => (
          <>
            <CpuPanel cpu={data.cpu} />
            <MemoryPanel memory={data.memory} />
            <StorageDevicesPanel devices={data.storageDevices} />
            <GpuPanel gpus={data.gpus} current={current.data ?? []} />
            <BatteryPanel battery={data.battery} />
            <Panel title="Network adapters">
              <NetworkAdaptersTable adapters={data.networkAdapters} />
            </Panel>
          </>
        )}
      </DataBoundary>
    </div>
  );
}
