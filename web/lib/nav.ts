// Sidebar navigation. `active` is computed from the current pathname at render
// time; the only declarative state here is label / icon / href.
export const navItems = [
  { label: "Dashboard", icon: "layout-dashboard", href: "/" },
  { label: "Goals", icon: "route", href: "/me/goals" },
  { label: "Body", icon: "body-scan", href: "/me/body-composition" },
  { label: "Blood", icon: "droplet", href: "/me/blood" },
  { label: "Workouts", icon: "barbell", href: "/me/workouts" },
  { label: "Meds", icon: "pill", href: "/me/meds" },
  { label: "Nutrition", icon: "bowl", href: "/me/nutrition" },
  { label: "Insights", icon: "sparkles", href: "/me/insights" },
];
