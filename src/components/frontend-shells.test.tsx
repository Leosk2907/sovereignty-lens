import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { AdminExperience } from "@/components/admin-experience";
import { Brand } from "@/components/brand";
import { ContributionForm } from "@/components/contribution-form";

describe("frontend shells", () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
  });

  it("links the shared brand to the live graph", () => {
    render(<Brand />);
    expect(screen.getByRole("link", { name: "Sovereignty Lens home" })).toHaveAttribute("href", "/");
  });

  it("loads canonical organizations into the contribution selector", async () => {
    render(<ContributionForm />);
    expect(await screen.findByRole("option", { name: /European Digital Services Agency/ })).toBeEnabled();
  });

  it("gates presenter controls behind the password form", async () => {
    render(<AdminExperience />);
    expect(await screen.findByLabelText("Presenter password")).toBeRequired();
  });
});
