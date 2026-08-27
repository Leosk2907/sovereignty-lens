import { describe, expect, it } from "vitest";
import { jurisdictionGroup } from "@/components/graph-canvas";

describe("graph visual grouping", () => {
  it("uses one coherent group for every external jurisdiction", () => {
    expect(jurisdictionGroup("united_states")).toBe("external");
    expect(jurisdictionGroup("china")).toBe("external");
    expect(jurisdictionGroup("other_external")).toBe("external");
  });

  it("keeps Europe and unresolved organizations distinct", () => {
    expect(jurisdictionGroup("europe")).toBe("europe");
    expect(jurisdictionGroup("unknown")).toBe("unknown");
  });
});
