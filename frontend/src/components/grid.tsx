"use client";

// Shared AG Grid setup: one module registration and one theme for every grid in the app.
import { AllCommunityModule, ModuleRegistry, themeQuartz } from "ag-grid-community";

ModuleRegistry.registerModules([AllCommunityModule]);

export const gridTheme = themeQuartz.withParams({
  accentColor: "#1e40af",
  borderColor: "#e2e8f0",
  headerBackgroundColor: "#f8fafc",
  headerTextColor: "#475569",
  headerFontWeight: 600,
  rowHoverColor: "#eff6ff",
  selectedRowBackgroundColor: "#dbeafe",
  oddRowBackgroundColor: "#fcfcfd",
  fontSize: 13,
  rowHeight: 44,
  headerHeight: 42,
  wrapperBorder: false,
  wrapperBorderRadius: 0,
});

/** Search box + filter chips + right-side actions, shown above a grid. */
export function GridToolbar<T extends string>({
  search, onSearch, placeholder, chips, active, onChip, children,
}: {
  search: string;
  onSearch: (v: string) => void;
  placeholder: string;
  chips: { id: T; label: string; count: number }[];
  active: T;
  onChip: (id: T) => void;
  children?: React.ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-center gap-2 border-b border-line bg-white px-3 py-2.5">
      <input
        value={search}
        onChange={(e) => onSearch(e.target.value)}
        placeholder={placeholder}
        aria-label={placeholder}
        className="input max-w-56 !py-1.5"
      />
      <div className="flex flex-wrap gap-1">
        {chips.map((c) => (
          <button
            key={c.id}
            onClick={() => onChip(c.id)}
            aria-pressed={active === c.id}
            className={`rounded-full px-3 py-1 text-xs font-medium transition ${
              active === c.id ? "bg-ink text-white" : "bg-slate-100 text-slate-600 hover:bg-slate-200"
            }`}
          >
            {c.label} <span className="opacity-70">{c.count}</span>
          </button>
        ))}
      </div>
      <div className="ml-auto flex items-center gap-2">{children}</div>
    </div>
  );
}
