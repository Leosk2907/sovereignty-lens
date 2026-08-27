import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { AdminExperience } from "@/components/admin-experience";
import { Brand } from "@/components/brand";
import { CompanyContributionForm } from "@/components/company-contribution-form";

describe("frontend shells", () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
  });

  it("links the shared brand to the live graph", () => {
    render(<Brand />);
    expect(screen.getByRole("link", { name: "Sovereignty Lens home" })).toHaveAttribute("href", "/");
  });

  it("loads canonical European customers into the company-profile form", async () => {
    render(<CompanyContributionForm />);
    expect(screen.getByLabelText("Your company name")).toBeRequired();
    const customer = await screen.findByRole("option", { name: /European Digital Services Agency/ });
    expect(customer).toBeEnabled();
    fireEvent.click(customer);
    expect(customer).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("1 / 3 selected")).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Europe" })).toBeInTheDocument();
  });

  it("gates presenter controls behind the password form", async () => {
    render(<AdminExperience />);
    expect(await screen.findByLabelText("Presenter password")).toBeRequired();
  });
});
