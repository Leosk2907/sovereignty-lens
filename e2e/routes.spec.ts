import { expect, test } from "@playwright/test";

test("public graph and contribution routes load", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "How far does dependency travel?" })).toBeVisible();
  await expect(page.getByText("Simulated, audience-submitted demo data")).toBeVisible();
  await page.goto("/contribute");
  await expect(page.getByRole("heading", { name: "Add one hidden dependency." })).toBeVisible();
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
  await expect(presentation.getByRole("heading", { name: "How far does dependency travel?" })).toBeVisible();

  const contribution = await context.newPage();
  await contribution.goto("/contribute?variant=external");
  const source = contribution.getByRole("option", { name: /European Digital Services Agency/ });
  await expect(source).toBeEnabled();
  await source.click();
  await expect(source).toHaveAttribute("aria-selected", "true");
  await contribution.getByLabel("Company name").fill("Atlantic Compute Inc");
  await contribution.getByLabel("Jurisdiction").selectOption("united_states");
  await contribution.getByRole("button", { name: "Add to the live graph →" }).click();
  await expect(contribution.getByRole("heading", { name: "Now watch the main screen." })).toBeVisible();

  await expect(presentation.getByText("Hidden dependency revealed")).toBeVisible();
  await expect(presentation.getByText(/steps to Atlantic Compute Inc/)).toBeVisible();
});

test("presenter can pause and resume submissions", async ({ page }) => {
  await page.goto("/admin");
  await page.getByLabel("Presenter password").fill("demo");
  await page.getByRole("button", { name: "Open presenter view →" }).click();
  await page.getByRole("button", { name: "Pause submissions" }).click();
  await expect(page.getByText("paused", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Resume submissions" }).click();
  await expect(page.getByText("open", { exact: true })).toBeVisible();
});
