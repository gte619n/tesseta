export type SpecSchema =
  | 'selectorized'
  | 'plate_loaded'
  | 'bodyweight'
  | 'cable'
  | 'cardio';

export type SelectorizedSpecs = {
  minWeight: number;
  maxWeight: number;
  increment: number;
};

export type PlateLoadedSpecs = {
  barWeight: number;
  availablePlates: number[];
};

export type CableSpecs = {
  weightStack: number;
  numStations: number;
};

export type CardioSpecs = {
  resistanceLevels: number;
  hasIncline: boolean;
};

export type EquipmentSpecs =
  | SelectorizedSpecs
  | PlateLoadedSpecs
  | Record<string, never>  // bodyweight
  | CableSpecs
  | CardioSpecs;

export type Equipment = {
  equipmentId: string;
  name: string;
  category: string;
  subcategory: string;
  specSchema: SpecSchema;
  specs: EquipmentSpecs;
  imageUrl: string | null;
  imageStatus: 'pending' | 'generated' | 'failed';
  ownerId: string | null;
  status: 'active' | 'pending_review' | 'rejected';
  contributorId: string | null;
  exerciseCount: number | null;
  createdAt: string;
  updatedAt: string;
};

export type DayOfWeek = 'mon' | 'tue' | 'wed' | 'thu' | 'fri' | 'sat' | 'sun';

export type HoursSlot = {
  open: string;  // HH:mm format
  close: string;
};

export type Location = {
  locationId: string;
  name: string;
  address: string | null;
  coverPhotoUrl: string | null;
  is24Hours: boolean;
  hours: Partial<Record<DayOfWeek, HoursSlot>> | null;
  amenities: string[];
  equipmentIds: string[];
  isDefault: boolean;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
};

export type Amenity = {
  id: string;
  label: string;
  icon: string;
};

export const AMENITIES: Amenity[] = [
  { id: '24hr', label: '24-Hour Access', icon: 'clock' },
  { id: 'lockers', label: 'Lockers', icon: 'lock' },
  { id: 'showers', label: 'Showers', icon: 'droplet' },
  { id: 'parking', label: 'Parking', icon: 'car' },
  { id: 'wifi', label: 'WiFi', icon: 'wifi' },
  { id: 'towels', label: 'Towels', icon: 'towel' },
  { id: 'sauna', label: 'Sauna', icon: 'flame' },
  { id: 'pool', label: 'Pool', icon: 'waves' },
  { id: 'childcare', label: 'Childcare', icon: 'baby' },
  { id: 'training', label: 'Personal Training', icon: 'user-check' },
];

export const EQUIPMENT_CATEGORIES = {
  'Free Weights': ['Barbells', 'Dumbbells', 'Kettlebells', 'Weight Plates', 'Other'],
  'Machines - Strength': ['Chest', 'Back', 'Shoulders', 'Arms', 'Legs', 'Core'],
  'Machines - Cardio': ['Treadmill', 'Elliptical', 'Stationary Bike', 'Rowing Machine', 'Stair Climber', 'Other'],
  'Cable Systems': ['Single Cable', 'Dual Cable', 'Multi-Station'],
  'Benches & Racks': ['Benches', 'Racks', 'Stations'],
  'Bodyweight': ['Pull-Up', 'Dip', 'Other'],
  'Accessories': ['Supports', 'Attachments', 'Mobility'],
} as const;

export type EquipmentCategory = keyof typeof EQUIPMENT_CATEGORIES;

// Request types for API
export type CreateLocationRequest = {
  name: string;
  address?: string | null;
  is24Hours: boolean;
  hours?: Partial<Record<DayOfWeek, HoursSlot>> | null;
  amenities: string[];
  equipmentIds?: string[];
};

export type UpdateLocationRequest = Partial<CreateLocationRequest>;

export type CreateEquipmentRequest = {
  name: string;
  category: string;
  subcategory: string;
  specSchema: SpecSchema;
  specs: EquipmentSpecs;
};
