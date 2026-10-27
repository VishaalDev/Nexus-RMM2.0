import DataBoundary from "../components/DataBoundary";
import DataTable from "../components/DataTable";
import PageHeader from "../components/PageHeader";
import Panel from "../components/Panel";
import RateCell from "../components/RateCell";
import StatStrip from "../components/StatStrip";
import StatusBadge from "../components/StatusBadge";
import UtilizationBar from "../components/UtilizationBar";
import { useCurrentMetrics, useHardwareSnapshot } from "../hooks/useApiData";
import { formatBytes } from "../utils/format";
import { findReadingByInstance, readingsOfType, usageLevel, USAGE_THRESHOLDS } from "../utils/metrics";

/**
 * Utilization prefers the live polled STORAGE_UTILIZATION reading for the
 * volume. If that isn't available yet, it is derived from the capacity
 * snapshot's total/usable bytes, which is equally real data.
 */
function volumeRow(volume, utilizationReadings) {
  const live = findReadingByInstance(utilizationReadings, volume.mount, volume.name);
  const usedBytes = Math.max(0, volume.totalBytes - volume.usableBytes);
  const derived = volume.totalBytes > 0 ? (usedBytes / volume.totalBytes) * 100 : null;
  const percent = live?.availabilityStatus === "AVAILABLE" ? live.value : derived;
  return { volume, usedBytes, percent, level: usageLevel(percent) };
}

const VOLUME_COLUMNS = [
  { key: "drive", header: "Drive", mono: true, nowrap: true, sortValue: (r) => r.volume.mount, render: (r) => r.volume.mount },
  { key: "label", header: "Name", wrap: "words", minWidth: "9rem", sortValue: (r) => r.volume.name, render: (r) => r.volume.name },
  { key: "fs", header: "File system", nowrap: true, render: (r) => r.volume.fileSystemType },
  { key: "total", header: "Total", align: "right", mono: true, nowrap: true, sortValue: (r) => r.volume.totalBytes, render: (r) => formatBytes(r.volume.totalBytes) },
  { key: "free", header: "Free", align: "right", mono: true, nowrap: true, sortValue: (r) => r.volume.usableBytes, render: (r) => formatBytes(r.volume.usableBytes) },
  { key: "used", header: "Used", align: "right", mono: true, nowrap: true, sortValue: (r) => r.usedBytes, render: (r) => formatBytes(r.usedBytes) },
  {
    key: "util",
    header: "Utilization",
    sortValue: (r) => r.percent,
    render: (r) =>
      r.percent === null ? (
        <span className="muted">N/A</span>
      ) : (
        <div className="value-cell compact">
          <span className="value-number">
            {r.percent.toFixed(1)}
            <span className="value-unit">%</span>
          </span>
          <UtilizationBar value={r.percent} level={r.level} label={`Utilization of ${r.volume.mount}`} />
        </div>
      ),
  },
  {
    key: "status",
    header: "Status",
    nowrap: true,
    render: (r) => (r.level ? <StatusBadge status={r.level.toUpperCase()} /> : <span className="muted">N/A</span>),
  },
];

function VolumesPanel({ volumes, current }) {
  const readings = readingsOfType(current, "STORAGE_UTILIZATION");
  const rows = volumes.map((v) => volumeRow(v, readings)).sort((a, b) => a.volume.mount.localeCompare(b.volume.mount));
  const highCount = rows.filter((r) => r.level === "high").length;
  const totalBytes = rows.reduce((sum, r) => sum + r.volume.totalBytes, 0);
  const usedBytes = rows.reduce((sum, r) => sum + r.usedBytes, 0);
  const fullest = rows.filter((r) => r.percent !== null).sort((a, b) => b.percent - a.percent)[0];

  return (
    <>
      {rows.length > 0 && (
        <StatStrip
          flat
          label="Storage summary"
          items={[
            { label: "Volumes", value: rows.length },
            {
              label: "Used of total",
              value: `${formatBytes(usedBytes)} of ${formatBytes(totalBytes)}`,
              sub: totalBytes > 0 ? `${((usedBytes / totalBytes) * 100).toFixed(1)}% across all volumes` : undefined,
            },
            {
              label: "Fullest volume",
              value: fullest ? `${fullest.percent.toFixed(1)}%` : "N/A",
              sub: fullest?.volume.mount,
            },
            {
              label: `At high utilization (${USAGE_THRESHOLDS.high}%+)`,
              value: `${highCount} of ${rows.length}`,
            },
          ]}
        />
      )}
      <DataTable
        caption="Storage volumes"
        stack
        columns={VOLUME_COLUMNS}
        rows={rows}
        getRowKey={(r) => `${r.volume.mount}|${r.volume.name}`}
        defaultSort={{ key: "drive", dir: "asc" }}
        emptyMessage="No volumes with a reported capacity were detected."
      />
    </>
  );
}

function DisksPanel({ devices, current }) {
  const reads = readingsOfType(current, "STORAGE_READ_BYTES_PER_SEC");
  const writes = readingsOfType(current, "STORAGE_WRITE_BYTES_PER_SEC");

  const columns = [
    { key: "name", header: "Device", mono: true, wrap: "anywhere", minWidth: "10rem", sortValue: (d) => d.name, render: (d) => d.name },
    { key: "model", header: "Model", wrap: "words", minWidth: "9rem", render: (d) => d.model },
    { key: "size", header: "Size", align: "right", mono: true, nowrap: true, sortValue: (d) => d.sizeBytes, render: (d) => formatBytes(d.sizeBytes) },
    {
      key: "smart",
      header: "SMART health",
      nowrap: true,
      render: (d) =>
        d.smartAvailable ? (
          <StatusBadge status="AVAILABLE" />
        ) : (
          <StatusBadge status="UNSUPPORTED" label="Not collected" />
        ),
    },
    {
      key: "read",
      header: "Read",
      nowrap: true,
      render: (d) => <RateCell reading={findReadingByInstance(reads, d.name)} missingTitle="No sample yet" />,
    },
    {
      key: "write",
      header: "Write",
      nowrap: true,
      render: (d) => <RateCell reading={findReadingByInstance(writes, d.name)} missingTitle="No sample yet" />,
    },
  ];

  return (
    <>
      <DataTable
        caption="Physical disks"
        stack
        columns={columns}
        rows={devices}
        getRowKey={(d) => d.name}
        emptyMessage="No physical disks were reported by this system."
      />
      {devices.length > 0 && (
        <p className="panel-foot">
          SMART and NVMe health attributes are not collected by this build, so drive health
          cannot be assessed here.
        </p>
      )}
    </>
  );
}

export default function Storage() {
  const hardware = useHardwareSnapshot();
  const current = useCurrentMetrics();

  return (
    <div className="page">
      <PageHeader
        title="Storage"
        subtitle="Capacity per volume and live throughput per physical disk."
        meta={<span>Utilization every 10 s</span>}
      />

      {current.error && current.data && (
        <p className="state state-warning" role="alert">
          Live utilization and throughput may be out of date: {current.error.message}
        </p>
      )}

      <Panel title="Volumes" flush>
        <DataBoundary state={hardware} what="storage volumes">
          {(data) => <VolumesPanel volumes={data.volumes} current={current.data ?? []} />}
        </DataBoundary>
      </Panel>

      <Panel title="Physical disks" flush>
        <DataBoundary state={hardware} what="physical disks">
          {(data) => <DisksPanel devices={data.storageDevices} current={current.data ?? []} />}
        </DataBoundary>
      </Panel>
    </div>
  );
}
