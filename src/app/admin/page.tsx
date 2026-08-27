import type { Metadata } from "next";
import { AdminExperience } from "@/components/admin-experience";

export const metadata: Metadata = { title: "Presenter controls" };

export default function AdminPage() {
  return <AdminExperience />;
}
