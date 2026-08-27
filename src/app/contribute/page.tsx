import type { Metadata } from "next";
import { CompanyContributionForm } from "@/components/company-contribution-form";

export const metadata: Metadata = { title: "Add your company" };

export default function ContributePage() {
  return <CompanyContributionForm />;
}
