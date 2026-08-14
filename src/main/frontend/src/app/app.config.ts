import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // Session-cookie (BFF) auth: same-origin calls carry the session cookie. Angular's XSRF support
    // reads the server's XSRF-TOKEN cookie and echoes it as the X-XSRF-TOKEN header on mutations,
    // matching Spring Security's CookieCsrfTokenRepository.
    provideHttpClient(
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })
    )
  ]
};
