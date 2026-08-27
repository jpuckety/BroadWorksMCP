import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Waits for `GET /api/portal/me`. On success, restores a stashed deep link (Google round-trip);
 * on 401, sends the user to `/login` with `returnUrl` for later stashing.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }

  return auth.me().pipe(
    map((user) => {
      if (!user) {
        return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
      }
      const stored = auth.consumeReturnUrl();
      if (stored && stored !== state.url) {
        return router.parseUrl(stored);
      }
      return true;
    })
  );
};
