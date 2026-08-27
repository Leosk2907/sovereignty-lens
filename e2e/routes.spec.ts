import { expect, test } from "@playwright/test";

test("public graph and contribution routes load", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "What does Europe depend on?" })).toBeVisible();
  await expect(page.getByLabel(/external dependencies revealed/)).toBeVisible();
  await expect(page.getByText("Simulated, audience-submitted demo data")).toBeVisible();
  await page.goto("/contribute");
  await expect(page.getByRole("heading", { name: "Add your company." })).toBeVisible();
  await expect(page.getByRole("group", { name: /Your company/ })).toBeVisible();
  await expect(page.getByRole("group", { name: /Your customers/ })).toBeVisible();
  await expect(page.getByRole("group", { name: /Your dependencies/ })).toBeVisible();
});

test("mock presenter can sign in", async ({ page }) => {
  await page.goto("/admin");
  await page.getByLabel("Presenter password").fill("demo");
  await page.getByRole("button", { name: "Open presenter view →" }).click();
  await expect(page.getByRole("heading", { name: "Round 1" })).toBeVisible();
});

test("a committed external dependency reveals on the live graph", async ({ context }) => {
  const presentation = await context.newPage();
  await presentation.goto("/");
  await expect(presentation.getByRole("heading", { name: "What does Europe depend on?" })).toBeVisible();

  const contribution = await context.newPage();
  await contribution.goto("/contribute");
  await contribution.getByLabel("Your company name").fill("Northstar Civic Systems");
  const customer = contribution.getByRole("option", { name: /European Digital Services Agency/ });
  await expect(customer).toBeEnabled();
  await customer.click();
  await contribution.getByLabel("Dependency company name").fill("Pacific Quantum Cloud");
  await contribution.getByLabel("Jurisdiction").selectOption("united_states");
  await contribution.getByRole("button", { name: "Add company profile to the graph →" }).click();
  await expect(contribution.getByRole("heading", { name: "Now watch the main screen." })).toBeVisible();

  await expect(presentation.getByText("External dependency revealed")).toBeVisible();
  await expect(presentation.getByText(/steps to Pacific Quantum Cloud/)).toBeVisible();
});

test("presenter can pause and resume submissions", async ({ page }) => {
  await page.goto("/admin");
  await page.getByLabel("Presenter password").fill("demo");
  await page.getByRole("button", { name: "Open presenter view →" }).click();
  await page.getByRole("button", { name: "Pause submissions" }).click();
  await expect(page.getByText("paused", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Resume submissions" }).click();
  await expect(page.getByText("open", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Open presenter inspector" }).click();
  await expect(page.getByText("Organizations", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Audience dependencies" })).toBeVisible();
});
