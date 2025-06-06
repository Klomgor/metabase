import { setupGdriveServiceAccountEndpoint } from "__support__/server-mocks";
import { screen } from "__support__/ui";
import { createMockUser } from "metabase-types/api/mocks";

import { type SetupOpts, setup } from "./setup";

const setupEnterprise = (opts?: SetupOpts) => {
  setupGdriveServiceAccountEndpoint();
  return setup({ ...opts, hasEnterprisePlugins: true });
};

describe("nav > containers > MainNavbar (EE without token)", () => {
  describe("DWH Upload", () => {
    it("should not render DWH Upload section", async () => {
      await setupEnterprise({ user: createMockUser({ is_superuser: true }) });
      expect(screen.queryByTestId("dwh-upload")).not.toBeInTheDocument();
    });
  });
});
