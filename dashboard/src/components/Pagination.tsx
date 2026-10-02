export function Pagination({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (p: number) => void }) {
  if (totalPages <= 1) return null;
  const btn = "rounded border border-slate-300 px-3 py-1 text-sm disabled:opacity-40 dark:border-slate-700";
  return (
    <nav className="flex items-center justify-end gap-3 pt-3" aria-label="Pagination">
      <button className={btn} disabled={page === 0} onClick={() => onChange(page - 1)}>
        Previous
      </button>
      <span className="text-sm text-slate-500">
        Page {page + 1} of {totalPages}
      </span>
      <button className={btn} disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
        Next
      </button>
    </nav>
  );
}
