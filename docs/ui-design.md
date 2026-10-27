# UI design system

The frontend uses one small design system. Nothing in `frontend/src/styles` or the components should introduce a colour, size or spacing value that is not a token from `styles/tokens.css`.

## Principles

- **Status first.** Overview opens with the Advisor's headline, the top issues (each with its first step), health score and failure risk. The headline readings follow, then history and detail.
- **Colour means something.** One accent (blue) marks interaction and normal data. Green, amber and red appear only for a real state, severity or threshold. Severity is always text plus a dot, never colour alone.
- **Detail on demand.** Issues show what is wrong and one primary action (plus any safety caution). Further steps, the reasoning and the raw evidence sit in a native `<details>` disclosure. Methodology, glossaries and limitations are collapsed the same way.
- **Honest values.** An unavailable reading is an em dash with the backend's own reason, never 0 and never a bar. Unchanged from before the redesign; covered by tests.

## Tokens (`styles/tokens.css`)

Dark by default, with a light theme through `prefers-color-scheme`. 4 px spacing scale, six type sizes (11.5 / 12 / 13 / 14 / 16 / 20 px, plus 24 px for headline readings), 4 px radius, `Segoe UI Variable` / `Segoe UI` with a system fallback, `Cascadia Mono` for identifiers. Numbers use tabular figures.

## Shared components (`frontend/src/components`)

| Component | Use |
|---|---|
| `Panel` | The only container. Optional header; `flush` for edge-to-edge tables |
| `PageHeader` | Title, one-line subtitle, right-aligned meta (timestamps, counts) |
| `StatStrip` | A row of labelled values separated by hairlines |
| `MetricTile` | One headline reading (value, bar, context) |
| `DataTable` | Dense table. A column with `sortValue` becomes sortable (`aria-sort`; missing values always sort last) |
| `Segmented`, `SelectField` | Compact choices |
| `Severity`, `StatusBadge`, `HealthBadge` | Dot plus text status |
| `SideNav` | Grouped navigation with the Advisor attention badge |

## Behaviour that did not change

All endpoints, polling cadences, hooks and API services are untouched. Every page, route and capability is still present. Processes now shows one sortable, filterable table of the union of the backend's top-by-CPU and top-by-memory lists instead of two separate tables; the same data, one place.
