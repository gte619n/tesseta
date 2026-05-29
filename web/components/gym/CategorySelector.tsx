"use client";

import { EQUIPMENT_CATEGORIES } from "@/lib/types/gym";

interface CategorySelectorProps {
  category: string;
  subcategory: string;
  onCategoryChange: (category: string) => void;
  onSubcategoryChange: (subcategory: string) => void;
  proposeNew: boolean;
  onProposeNewChange: (propose: boolean) => void;
  newSubcategoryName: string;
  onNewSubcategoryNameChange: (name: string) => void;
}

export function CategorySelector({
  category,
  subcategory,
  onCategoryChange,
  onSubcategoryChange,
  proposeNew,
  onProposeNewChange,
  newSubcategoryName,
  onNewSubcategoryNameChange,
}: CategorySelectorProps) {
  const categories = Object.keys(EQUIPMENT_CATEGORIES);
  const subcategories = category ? EQUIPMENT_CATEGORIES[category as keyof typeof EQUIPMENT_CATEGORIES] : [];

  function handleCategoryChange(newCategory: string) {
    onCategoryChange(newCategory);
    // Reset subcategory when category changes
    onSubcategoryChange("");
    onProposeNewChange(false);
    onNewSubcategoryNameChange("");
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-4">
        {/* Category Dropdown */}
        <div>
          <label htmlFor="category" className="mb-1.5 block text-[13px] font-medium text-primary">
            Category *
          </label>
          <select
            id="category"
            value={category}
            onChange={(e) => handleCategoryChange(e.target.value)}
            className="w-full appearance-none rounded-md border border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          >
            <option value="">Select category...</option>
            {categories.map((cat) => (
              <option key={cat} value={cat}>
                {cat}
              </option>
            ))}
          </select>
        </div>

        {/* Subcategory Dropdown */}
        <div>
          <label htmlFor="subcategory" className="mb-1.5 block text-[13px] font-medium text-primary">
            Subcategory *
          </label>
          <select
            id="subcategory"
            value={subcategory}
            onChange={(e) => onSubcategoryChange(e.target.value)}
            disabled={!category || proposeNew}
            className="w-full appearance-none rounded-md border border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent disabled:cursor-not-allowed disabled:opacity-50"
          >
            <option value="">Select subcategory...</option>
            {subcategories.map((sub) => (
              <option key={sub} value={sub}>
                {sub}
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* Propose New Subcategory */}
      {category && (
        <div className="space-y-2">
          <label className="flex cursor-pointer items-center gap-2">
            <input
              type="checkbox"
              checked={proposeNew}
              onChange={(e) => {
                onProposeNewChange(e.target.checked);
                if (e.target.checked) {
                  onSubcategoryChange("");
                }
              }}
              className="h-4 w-4 cursor-pointer rounded border-border-default accent-accent focus:ring-2 focus:ring-accent"
            />
            <span className="text-[13px] text-secondary">Propose new subcategory</span>
          </label>

          {proposeNew && (
            <input
              type="text"
              value={newSubcategoryName}
              onChange={(e) => onNewSubcategoryNameChange(e.target.value)}
              placeholder="Enter new subcategory name..."
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-[13px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          )}
        </div>
      )}
    </div>
  );
}
