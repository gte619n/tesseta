import { redirect } from 'next/navigation';
import { pageMetadata } from '@/lib/page-metadata';

export const metadata = pageMetadata('Equipment');

export default function Page() {
  redirect('/admin/equipment/catalog');
}
