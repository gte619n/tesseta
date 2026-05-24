"use client";

interface CategoryFilterProps {
  categories: Record<string, readonly string[]>;
  selectedCategory: string | null;
  selectedSubcategory: string | null;
  onCategoryChange: (category: string | null) => void;
  onSubcategoryChange: (subcategory: string | null) => void;
}

export function EquipmentCategoryFilter({
  categories,
  selectedCategory,
  selectedSubcategory,
  onCategoryChange,
  onSubcategoryChange,
}: CategoryFilterProps) {
  const subcategories = selectedCategory ? (categories[selectedCategory] ?? []) : [];

  return (
    <div className="flex gap-2">
      <select
        value={selectedCategory ?? ""}
        onChange={(e) => {
          const value = e.target.value || null;
          onCategoryChange(value);
          onSubcategoryChange(null); // Reset subcategory when category changes
        }}
        className="rounded-md border border-border-default bg-canvas px-3 py-1.5 text-[13px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent/20"
      >
        <option value="">All Categories</option>
        {Object.keys(categories).map((category) => (
          <option key={category} value={category}>
            {category}
          </option>
        ))}
      </select>

      {selectedCategory && subcategories.length > 0 && (
        <select
          value={selectedSubcategory ?? ""}
          onChange={(e) => onSubcategoryChange(e.target.value || null)}
          className="rounded-md border border-border-default bg-canvas px-3 py-1.5 text-[13px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent/20"
        >
          <option value="">All Subcategories</option>
          {[...subcategories].map((sub) => (
            <option key={sub} value={sub}>
              {sub}
            </option>
          ))}
        </select>
      )}
    </div>
  );
}
