import { routes, type VercelConfig } from "@vercel/config/v1";

/**
 * Vercel project configuration.
 *
 * <p>The rewrite is not a convenience — it is load-bearing for authentication.
 *
 * <p>PATIPP's refresh token lives in an httpOnly cookie marked {@code SameSite=Strict},
 * chosen so the browser never attaches it to a cross-site request. If the frontend were served
 * from {@code patipp.ebitimi.dev} and the API from {@code patipp-api.fly.dev}, the browser would
 * treat every call as cross-site and silently refuse to send that cookie. Sign-in would appear
 * to succeed and then no session would survive a page reload — and it would read as a bug in the
 * auth code rather than a consequence of where things are hosted.
 *
 * <p>Proxying {@code /api/*} through this domain means the browser only ever sees one origin, so
 * {@code SameSite=Strict} keeps working exactly as designed. The alternative — relaxing the
 * cookie to {@code SameSite=None} — would discard CSRF protection that was deliberate.
 */
export const config: VercelConfig = {
  framework: "nextjs",

  rewrites: [
    // Proxied, not redirected: a redirect would send the browser to the Fly origin and put us
    // straight back into the cross-site problem this exists to avoid.
    routes.rewrite(
      "/api/(.*)",
      `${process.env.PATIPP_API_ORIGIN ?? "https://patipp-api.fly.dev"}/api/$1`,
    ),
  ],

  // No cache headers for /api on purpose. Spring Security already sends
  // `no-store, must-revalidate` on authenticated responses, and a rule here would override what
  // the API itself says about its own responses rather than adding anything.
};
