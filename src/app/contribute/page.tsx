import type { Metadata } from "next";
import { ContributionForm } from "@/components/contribution-form";

export const metadata: Metadata = { title: "Add a dependency" };

export default function ContributePage() {
  return <ContributionForm />;
}
