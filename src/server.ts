import { app } from "./app";
import { config } from "./config/env";

app.listen(config.port, () => {
  console.log(`[se330-payment-be] listening on http://localhost:${config.port}`);
  console.log(`[se330-payment-be] CORS allowed origin: ${config.frontendUrl}`);
});
