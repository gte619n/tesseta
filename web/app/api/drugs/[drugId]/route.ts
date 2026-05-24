import { apiJson } from "@/lib/api";
import { NextResponse } from "next/server";
import type { Drug } from "@/lib/types/medication";

export async function GET(
  _request: Request,
  { params }: { params: Promise<{ drugId: string }> }
) {
  const { drugId } = await params;

  try {
    const drug = await apiJson<Drug>(`/api/drugs/${drugId}`);
    return NextResponse.json(drug);
  } catch (error) {
    return new NextResponse(
      error instanceof Error ? error.message : "Failed to fetch drug",
      { status: 500 }
    );
  }
}
